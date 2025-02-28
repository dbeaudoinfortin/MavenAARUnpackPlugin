package com.dbf.maven.aar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Repository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import org.codehaus.plexus.archiver.zip.ZipUnArchiver;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

import com.dbf.utils.stacktrace.StackTraceCompactor;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.plugins.annotations.LifecyclePhase;

/**
 * Mojo to unpack Android Archive Library files
 */
@SuppressWarnings("deprecation")
@Mojo(name = "aar-unpack", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class AarUnpackMojo extends AbstractMojo {
	
	//---Configurable parameters---
	
	/**
     * Optional list Android Archive Libraries to include as dependencies.
     * Entries need to be in the form of Maven dependency coordinates:
     * &lt;groupId&gt;:&lt;artifactId&gt;[:&lt;extension&gt;[:&lt;classifier&gt;]]:&lt;version&gt;
     * 
     * If absent, the project will be automatically scanned for dependencies of type 'aar'.
     */
	@Parameter(property = "aars")
    private List<String> aars;
	
	/**
     * The target directory for the temporary extraction Android Archive Library files.
     */
	@Parameter(property = "extractionDir", defaultValue = "${project.build.directory}/aar-extracted", required = true)
    private String extractionDir;
	
	//---Injected Classes---
	@Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
	
	@Inject
    private RepositorySystem repoSystem;

	@Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSystemSession;
	
	@Parameter(defaultValue = "${project.remotePluginRepositories}", readonly = true)
    private List<RemoteRepository> remotePluginRepos;

	@Parameter(defaultValue = "${project.repositories}", readonly = true)
	private List<Repository> projectRepos;

	@Override
    public void execute() throws MojoExecutionException {
		
		//We want to merge the two sets of repos together in case there are special repos defined just for plugins
		List<RemoteRepository> mergedRepos = mergeRespositories();
		
		boolean reloadDependencies = false;
		if(aars == null || aars.isEmpty()) {
			//No AAR dependencies were explicitly defined. Automatically scan the project to find AAR dependencies
			for (Dependency dependency : project.getDependencies()) {
				// We only care about AARs for this plug-in
				if (!"aar".equals(dependency.getType().toLowerCase())) continue;
				
				//Manually force the resolution of this artifact since Maven can't manually resolve it.
				Artifact artifact = resolveAAR(new DefaultArtifact(dependency.getGroupId(), dependency.getArtifactId(), dependency.getClassifier(), "aar", dependency.getVersion()), mergedRepos);
				
				//Modify the dependency to make it a system look up
				dependency.setScope("system");
				dependency.setType("jar");
				dependency.setSystemPath(artifact.getFile().getAbsolutePath());
				reloadDependencies = true;
			}
		} else {
			//Explicitly defined dependencies
			for (String aar: aars) {
				try {
					if (!aar.contains(":"))
						throw new IllegalArgumentException("Missing ':' character.");
			        	
					//Process the AAR by resolving the artifact using the coordinates. Ex. "androidx.graphics:graphics-core:1.0.2"
		        	getLog().info("Explicitly defined AAR dependency: " + aar);
		        	Artifact artifact = new DefaultArtifact(aar);
		        	
		        	//Need to override the default extension of jar to aar
		        	artifact = resolveAAR(new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(), "aar", artifact.getVersion()), mergedRepos);
	
		        	//Need to add a new dependency to the main project
		        	//This time it's a jar file making use of a system path
		        	Dependency dependency = new Dependency();
		        	dependency.setArtifactId(artifact.getArtifactId());
		        	dependency.setGroupId(artifact.getGroupId());
		        	dependency.setClassifier(artifact.getClassifier());
		        	dependency.setVersion(artifact.getVersion());
		        	dependency.setSystemPath(artifact.getFile().getAbsolutePath());
		        	dependency.setScope("system");
					dependency.setType("jar");
					
					getLog().info("Adding a new project dependency for AAR: " + aar);
					project.getDependencies().add(dependency);
					reloadDependencies = true;		        	
				} catch (IllegalArgumentException e) {
					getLog().error("Unresolvable AAR: " + aar + ". Format should be <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>");
					throw new MojoExecutionException("Error resolving AAR file: " + aar, e);
				}
			}
		}
		
		if(reloadDependencies) {
			//We have modified the dependencies so now we must force a reload before the compilation lifecycle phase
			getLog().info("Forcing project dependency reload.");
			project.setResolvedArtifacts(null);
			project.setDependencyArtifacts(null);
		}
    }
	
	/**
	 * Resolves a provided AAR artifact based on a provided list of repositories.
	 * 
	 * @param unresolvedArtifact AAR artifact to be resolved.
	 * @param repos List of repositories used for the resolution of the artifact.
	 * @return A resolved AAR artifact. May or may not exactly match the provided artifact.
	 * @throws MojoExecutionException if the AAR artifact could not be resolved
	 */
	private Artifact resolveAAR(Artifact unresolvedArtifact, List<RemoteRepository> repos) throws MojoExecutionException {
		final String aarFQN = convertToFQN(unresolvedArtifact);
		getLog().info("Resolving AAR: " + aarFQN);
		try {		
			Artifact resolvedArtifact = resolveArtifact(unresolvedArtifact, repos);
			final File jarFile = extractAAR(resolvedArtifact.getFile(), aarFQN);
			return resolvedArtifact.setFile(jarFile);
		} catch (Exception e) {
			getLog().error("Error resolving AAR file: " + aarFQN + "\n" + StackTraceCompactor.getCompactStackTrace(e));
			throw new MojoExecutionException("Error resolving AAR file: " + aarFQN, e);
		}
	} 
	
	/**
	 * Extracts the provided AAR file to a target sub-directory and returns the absolute path to the
	 * extracted classes.jar file that was within the AAR file.
	 * 
	 * @param aarFile AAR file to extract
	 * @param aarFQN Fully qualified (globally unique) name for the AAR file. Used as the sub-directory name.
	 * 
	 * @return File pointing to the extracted classes.jar file.
	 * @throws MojoExecutionException if the AAR file does not exist, does not contain 'classes.jar' within it, or cannot be extracted.
	 */
	private File extractAAR(File aarFile, String aarFQN) throws MojoExecutionException {
		getLog().info("Processing AAR: " + aarFQN);
		if (!aarFile.exists()) {
            throw new MojoExecutionException("AAR file not found: " + aarFQN);
        }
    	
		//Each AAR needs its own sub-directory because they all contain the same classes.jar file inside
    	File aarDir = new File(extractionDir + File.separator + aarFQN);
  
    	getLog().info("Extracting AAR \"" + aarFile.getAbsolutePath()  + "\" to \"" + aarDir.getAbsolutePath() + "\".");
        try {
        	//Create a unique directory in the target directory
        	aarDir.mkdirs();
        	
        	//Extract the AAR file to a unique directory in the target directory
        	ZipUnArchiver unArchiver = new ZipUnArchiver(aarFile);
            unArchiver.setDestDirectory(aarDir);
            unArchiver.extract();
            getLog().info("Successfully extracted AAR: " + aarFile.getAbsolutePath());
        } catch (Exception e) {
            throw new MojoExecutionException("Error extracting AAR", e);
        }
        
        //Check if the "classes.jar" file if present
        File classesJar = new File(aarDir + File.separator + "classes.jar");
        if(!classesJar.isFile()) {
        	throw new MojoExecutionException("Missing 'classes.jar' in AAR: " + aarFQN);
        }
        
        //Return the location of the classes.jar file that was just extracted
        return classesJar;
	}
	
	private Artifact resolveArtifact(Artifact artifact, List<RemoteRepository> repos) throws ArtifactResolutionException {
		ArtifactRequest request = new ArtifactRequest();
		request.setArtifact(artifact);
		request.setRepositories(repos);

		ArtifactResult result = repoSystem.resolveArtifact(repoSystemSession, request); //Always throws an exception if not found
		return result.getArtifact();
	}
	
	private List<RemoteRepository> mergeRespositories() throws MojoExecutionException {
		//This allows us to use either the plugin-specific repositories or main project repositories
		getLog().info("Using both project repositories and remote plugin repositories for artifact resolution.");
		
		List<RemoteRepository> mergedRepos = new ArrayList<RemoteRepository>();
		try {		
			
			if(null != remotePluginRepos) {
				mergedRepos.addAll(remotePluginRepos);
				for(RemoteRepository remoteRepo : remotePluginRepos) {
					getLog().info("Using remote plugin respository: " + remoteRepo.getId());
				}
			}
			
			if(null != projectRepos && !projectRepos.isEmpty()) {
				projectRepos.stream().map(r-> convertToRemoteRepository(r)).forEach(r->{
					getLog().info("Using project respository: " + r.getId());
					mergedRepos.add(r);
				});
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new MojoExecutionException("Failed to merge repositories.", e);
		}
		return mergedRepos;
	}
	
	private static RemoteRepository convertToRemoteRepository(Repository repo) {
        //Convert a regular repository to a plugin remote repository
        return new RemoteRepository.Builder(repo.getId(), repo.getLayout(), repo.getUrl())
                .setReleasePolicy(convertRepositoryPolicy(repo.getReleases()))
                .setSnapshotPolicy(convertRepositoryPolicy(repo.getSnapshots()))
                .build();
    }

    private static RepositoryPolicy convertRepositoryPolicy(org.apache.maven.model.RepositoryPolicy mavenPolicy) {
    	if(mavenPolicy == null) return null;
        return new RepositoryPolicy(mavenPolicy.isEnabled(), 
                                    mavenPolicy.getUpdatePolicy(),
                                    mavenPolicy.getChecksumPolicy());
    }
    
	private static String convertToFQN(Artifact artifact) {
		StringBuilder sb = new StringBuilder();
		sb.append(artifact.getGroupId());
		sb.append("-");
		sb.append(artifact.getArtifactId());
		
		if (artifact.getClassifier() != null && !artifact.getClassifier().isEmpty()) {
			sb.append("-");
			sb.append(artifact.getClassifier());
		}
		
		sb.append("-");
		sb.append(artifact.getVersion());
		return sb.toString();
	}
}
