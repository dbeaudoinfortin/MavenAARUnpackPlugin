package com.dbf.maven.aar;

import java.io.File;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;

import org.apache.commons.io.FileUtils;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
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
	
	private static final String CONTEXT_CLASSPATH = "aar-unpack-classpath";
	private static final String CONTEXT_EXTRACT_DIR = "aar-unpack-extract-dir";
	
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
     * Optional parameter to indicate that the sources JARs should also be copied to the
     * extraction directory, if they exist.
     * 
     * Default value is false.
     */
	@Parameter(property = "copySources", defaultValue = "false", required = false)
    private boolean copySources = false;
	
	/**
     * Optional parameter to specify the target directory used for the temporary extraction
     * Android Archive Library files.
     * 
     * Default value is ${project.build.directory}/aar-extracted, typically corresponding to
     * /target/extracted.
     */
	@Parameter(property = "extractionDir", defaultValue = "${project.build.directory}/aar-extracted", required = true)
    private String extractionDir;
	
	//---Injected Classes---
	@Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
	
	@Inject
	private MavenSession session;
	
	@Inject
    private RepositorySystem repoSystem;

	@Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSystemSession;
	
	@Parameter(defaultValue = "${project.remotePluginRepositories}", readonly = true)
    private List<RemoteRepository> remotePluginRepos;

	@Parameter(defaultValue = "${project.repositories}", readonly = true)
	private List<Repository> projectRepos;

	@SuppressWarnings("unchecked")
	@Override
    public void execute() throws MojoExecutionException {
		
		//We want to merge the two sets of repos together in case there are special repos defined just for plugins
		List<RemoteRepository> mergedRepos = mergeRespositories();
		
		//Keep track of all of the classpath changes that we are making
		//This is in the form of <Classes JAR, Sources JAR>
		List<Map.Entry<String, String>> classpathChanges = null;
		Object classpathChangesO =  project.getContextValue(CONTEXT_CLASSPATH);
		if(null != classpathChangesO && classpathChangesO instanceof List<?>) {
			classpathChanges = (List<Map.Entry<String, String>>) classpathChangesO;
		}
		if(null == classpathChanges) {
			classpathChanges = new ArrayList<Map.Entry<String, String>>();
		}

		boolean reloadDependencies = false;
		if(aars == null || aars.isEmpty()) {
			//No AAR dependencies were explicitly defined. Automatically scan the project to find AAR dependencies
			for (Dependency dependency : project.getDependencies()) {
				// We only care about AARs for this plug-in
				if (!"aar".equals(dependency.getType().toLowerCase())) continue;
				
				//Manually force the resolution of this artifact since Maven can't manually resolve it.
				Map.Entry<Artifact, Artifact> resolvedResult = resolveAAR(new DefaultArtifact(dependency.getGroupId(), dependency.getArtifactId(), dependency.getClassifier(), "aar", dependency.getVersion()), mergedRepos);
				Artifact artifact = resolvedResult.getKey();
				final Artifact sources = resolvedResult.getValue();
				final String jarPath = artifact.getFile().getAbsolutePath();
				classpathChanges.add(new AbstractMap.SimpleEntry<String, String>(jarPath, sources == null ? null : sources.getFile().getAbsolutePath()));
				
				//Modify the dependency to make it a system look up
				dependency.setScope("system");
				dependency.setType("jar");
				dependency.setSystemPath(jarPath);
				
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
		        	Map.Entry<Artifact, Artifact> resolvedResult =  resolveAAR(new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(), "aar", artifact.getVersion()), mergedRepos);
		        	artifact = resolvedResult.getKey();
		        	final Artifact sources = resolvedResult.getValue();
		        	final String jarPath = artifact.getFile().getAbsolutePath();
					classpathChanges.add(new AbstractMap.SimpleEntry<String, String>(jarPath, sources == null ? null : sources.getFile().getAbsolutePath()));
					
		        	//Need to add a new dependency to the main project
		        	//This time it's a jar file making use of a system path
		        	Dependency dependency = new Dependency();
		        	dependency.setArtifactId(artifact.getArtifactId());
		        	dependency.setGroupId(artifact.getGroupId());
		        	dependency.setClassifier(artifact.getClassifier());
		        	dependency.setVersion(artifact.getVersion());
		        	dependency.setSystemPath(jarPath);
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
		
		//Keep track of all of the classpath entries. This is useful for plug-ins. 
		project.setContextValue(CONTEXT_CLASSPATH, classpathChanges);
		
		//Keep track of the directory that was used for extraction
		project.setContextValue(CONTEXT_EXTRACT_DIR, (new File(extractionDir + File.separator)).getAbsolutePath());
    }
	
	/**
	 * Resolves a provided AAR artifact based on a provided list of repositories.
	 * 
	 * @param unresolvedArtifact AAR artifact to be resolved.
	 * @param repos List of repositories used for the resolution of the artifact.
	 * @return A resolved AAR artifact. May or may not exactly match the provided artifact.
	 * @throws MojoExecutionException if the AAR artifact could not be resolved
	 */
	private Map.Entry<Artifact, Artifact> resolveAAR(Artifact unresolvedArtifact, List<RemoteRepository> repos) throws MojoExecutionException {
		final String aarFQN = convertToFQN(unresolvedArtifact);
		getLog().info("Resolving AAR: " + aarFQN);
		try {
			Map.Entry<Artifact, Artifact> resolvedResult = resolveArtifact(unresolvedArtifact, repos);
			Artifact resolvedAARArtifact = resolvedResult.getKey();
			final File jarFile = extractAAR(resolvedAARArtifact.getFile(), aarFQN);
			return new AbstractMap.SimpleEntry<Artifact, Artifact>(resolvedAARArtifact.setFile(jarFile), resolvedResult.getValue());
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
    	File extractDir = new File(extractionDir + File.separator + aarFQN);
    	
    	final String aarAbsolutePath = aarFile.getAbsolutePath();
    	final String extractDirAbsolutePath = extractDir.getAbsolutePath();
  
    	getLog().info("Extracting AAR \"" + aarAbsolutePath  + "\" to \"" + extractDirAbsolutePath + "\".");
        try {
        	if(extractDir.exists()) {
        		if(extractDir.isDirectory()) {
        			MavenExecutionRequest request = session.getRequest();
        	        if(request.isUpdateSnapshots()) {
        	        	//Forcing the update of snapshots
        	        	getLog().debug("Deleting directory \"" + extractDirAbsolutePath + "\".");
        	        	FileUtils.deleteDirectory(extractDir);
        	        	unZipAAR(aarFile, extractDir);
        	        }
        	        //Otherwise do nothing. Use the already extracted AAR
            	} else {
            		getLog().warn("Conflicting file found at \"" + extractDirAbsolutePath + "\".");
            		getLog().debug("Deleting file \"" + extractDirAbsolutePath + "\".");
            		FileUtils.delete(extractDir);
            		unZipAAR(aarFile, extractDir);
            	}
        	} else {
        		getLog().debug("Creating directory \"" + extractDirAbsolutePath + "\".");
        		//Create a unique directory in the target directory
            	extractDir.mkdirs();
            	unZipAAR(aarFile, extractDir);
        	}        	
        } catch (Exception e) {
            throw new MojoExecutionException("Error extracting AAR", e);
        }
        
        //Check if the "classes.jar" file if present
        File classesJar = new File(extractDir + File.separator + "classes.jar");
        if(!classesJar.isFile()) {
        	throw new MojoExecutionException("Missing \"classes.jar\" in AAR: " + aarFQN);
        }
        
        if(copySources) {
        	//Copy the sources JAR as well, if present
            if(aarAbsolutePath.toLowerCase().endsWith(".aar")) { //Basic sanity check
            	File sourcesJar = new File(aarAbsolutePath.substring(0, aarAbsolutePath.length()-4) + "-sources.jar");
            	try {
    	        	if(sourcesJar.isFile()) {
    	        		getLog().debug("Copying sources JAR \"" + sourcesJar + "\" to directory \"" + extractDirAbsolutePath + "\".");
    	        		FileUtils.copyFileToDirectory(sourcesJar, extractDir);
    	        	}
    	        } catch (IOException e) {
    				getLog().debug("Error copying sources JAR \"" + sourcesJar + "\" to directory \"" + extractDirAbsolutePath + "\": + \n" + StackTraceCompactor.getCompactStackTrace(e));
    				//Don't throw an exception, source files are optional
    			}
            }
        }
		
        //Return the location of the classes.jar file that was just extracted
        return classesJar;
	}
	
	private void unZipAAR(File aarFile, File extractDir) {
		//Extract the AAR file to a unique directory in the target directory
    	ZipUnArchiver unArchiver = new ZipUnArchiver(aarFile);
        unArchiver.setDestDirectory(extractDir);
        unArchiver.extract();
        getLog().info("Successfully extracted AAR: " + aarFile.getAbsolutePath());
	}
	
	private Map.Entry<Artifact, Artifact> resolveArtifact(Artifact artifact, List<RemoteRepository> repos) throws ArtifactResolutionException {
		ArtifactRequest request = new ArtifactRequest();
		request.setArtifact(artifact);
		request.setRepositories(repos);

		ArtifactResult result = repoSystem.resolveArtifact(repoSystemSession, request); //Always throws an exception if not found
		
		//Also attempt to automatically download the sources
		ArtifactResult sourcesResult = null;
		if(artifact.getClassifier() == null || !artifact.getClassifier().toLowerCase().equals("sources")) {
			Artifact sourcesArtifact = new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), "sources", "jar", artifact.getVersion());

			try {
				ArtifactRequest sourcesRequest = new ArtifactRequest();
				sourcesRequest.setArtifact(sourcesArtifact);
				sourcesRequest.setRepositories(repos);
				sourcesResult = repoSystem.resolveArtifact(repoSystemSession, sourcesRequest);
			} catch (ArtifactResolutionException e) {
				getLog().debug("Unable to resolve sources JAR " + convertToFQN(sourcesArtifact) + "\n" + StackTraceCompactor.getCompactStackTrace(e));
				//Don't throw an exception, source files are optional
			}
		}
		return new AbstractMap.SimpleEntry<Artifact, Artifact>(result.getArtifact(), sourcesResult == null ? null : sourcesResult.getArtifact());
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
