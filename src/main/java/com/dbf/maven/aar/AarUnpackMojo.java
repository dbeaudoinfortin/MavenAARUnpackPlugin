package com.dbf.maven.aar;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import org.codehaus.plexus.archiver.zip.ZipUnArchiver;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;

/**
 * Mojo to unpack Android Archive Library files
 */
@Mojo(name = "aar-unpack", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class AarUnpackMojo extends AbstractMojo {
	
	/**
     * The location of the AAR file.
     */
	@Parameter(property = "aars", required = true)
    private List<String> aars;
	
	@Component
    private RepositorySystem repoSystem;

	@Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repositorySystemSession;
	
	@Parameter(defaultValue = "${project.remotePluginRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    /**
     * The output directory.
     */
	@Parameter(property = "outputDir", defaultValue = "${project.build.directory}/aar-extracted", required = true)
    private String outputDir;

	@Override
    public void execute() throws MojoExecutionException {
		
		for (String aar: aars) {
			File aarFile;
	        if (aar.contains(":")) {
	        	//Resolve the AAR file if path.
	        	//Ex. "androidx.graphics:graphics-core:1.0.2"
				try {
					Artifact artifact = new DefaultArtifact(aar);
				
					//Override the default type of jar to aar
					artifact = new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(), "aar", artifact.getVersion());
					
					ArtifactRequest request = new ArtifactRequest();
					request.setArtifact(artifact);
					request.setRepositories(remoteRepos);
					
					ArtifactResult result = repoSystem.resolveArtifact(repositorySystemSession, request);
					aarFile = result.getArtifact().getFile();
				} catch (Exception e) {
					e.printStackTrace();
					throw new MojoExecutionException("Error resolving AAR file: " + aar, e);
				}
	        } else {
	        	//Assume this is a file path. Relative or absolute.
	        	aarFile = new File(aar);
	        }
	        
	    	if (!aarFile.exists()) {
	            throw new MojoExecutionException("AAR file not found: " + aar);
	        }
	    	
	    	String out = outputDir + File.separator + aar.replaceAll("[:]", "-");
	    	getLog().info("Extracting AAR \"" + aarFile.getAbsolutePath()  + "\" to \"" +  out + "\".");
	    	
	        try {
	        	File subDir = new File(out);
	        	subDir.mkdirs();
	        	ZipUnArchiver unArchiver = new ZipUnArchiver(aarFile);
	            unArchiver.setDestDirectory(new File(out));
	            unArchiver.extract();
	            getLog().info("Successfully extracted AAR: " + aarFile.getAbsolutePath());
	        } catch (Exception e) {
	        	e.printStackTrace();
	            throw new MojoExecutionException("Error extracting AAR", e);
	        }
		}
    }
}
