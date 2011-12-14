package gov.loc.repository.bagit.verify.impl;

import static java.text.MessageFormat.*;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import gov.loc.repository.bagit.Bag;
import gov.loc.repository.bagit.BagFile;
import gov.loc.repository.bagit.Manifest;
import gov.loc.repository.bagit.filesystem.DirNode;
import gov.loc.repository.bagit.filesystem.FileNode;
import gov.loc.repository.bagit.filesystem.FileSystemFactory;
import gov.loc.repository.bagit.filesystem.FileSystemFactory.UnsupportedFormatException;
import gov.loc.repository.bagit.filesystem.FileSystemNode;
import gov.loc.repository.bagit.filesystem.filter.AndFileSystemNodeFilter;
import gov.loc.repository.bagit.filesystem.filter.DirNodeFileSystemNodeFilter;
import gov.loc.repository.bagit.filesystem.filter.FileNodeFileSystemNodeFilter;
import gov.loc.repository.bagit.filesystem.filter.IgnoringFileSystemNodeFilter;
import gov.loc.repository.bagit.utilities.BagVerifyResult;
import gov.loc.repository.bagit.utilities.FilenameHelper;
import gov.loc.repository.bagit.utilities.FormatHelper.UnknownFormatException;
import gov.loc.repository.bagit.utilities.LongRunningOperationBase;
import gov.loc.repository.bagit.utilities.SimpleResult;
import gov.loc.repository.bagit.verify.CompleteVerifier;

public class CompleteVerifierImpl extends LongRunningOperationBase implements CompleteVerifier {

	private static final Log log = LogFactory.getLog(CompleteVerifierImpl.class);
	
	private boolean missingBagItTolerant = false;
	private boolean additionalDirectoriesInBagDirTolerant = false;	
	private List<String> ignoreAdditionalDirectories = new ArrayList<String>();
	private boolean ignoreSymlinks = false;
	
	@Override
	public void setIgnoreSymlinks(boolean ignore) {
		this.ignoreSymlinks = ignore;
	}
	
	@Override
	public void setMissingBagItTolerant(boolean missingBagItTolerant) {
		this.missingBagItTolerant = missingBagItTolerant;
	}
	
	@Override
	public void setAdditionalDirectoriesInBagDirTolerant(
			boolean additionalDirectoriesInBagDirTolerant) {
		this.additionalDirectoriesInBagDirTolerant = additionalDirectoriesInBagDirTolerant;
		
	}
	
	@Override
	public void setIgnoreAdditionalDirectories(List<String> dirs) {
		this.ignoreAdditionalDirectories = dirs;
	}
	
	@Override
	public SimpleResult verify(Bag bag) {
		BagVerifyResult result = new BagVerifyResult(true);
		//Is at least one payload manifest
		log.debug("Checking that at least one payload manifest");
		if (bag.getPayloadManifests().isEmpty()) {
			result.setSuccess(false);
			result.addMessage("Bag does not have any payload manifests.");
		}
		//Has bagit file
		log.debug("Checking that has BagIt.txt");
		if (! this.missingBagItTolerant && bag.getBagItTxt() == null) {
			result.setSuccess(false);
			result.addMessage(MessageFormat.format("Bag does not have {0}.", bag.getBagConstants().getBagItTxt()));				
		}
		
		//Bagit is right version
		log.debug("Checking that BagIt.txt is right version");
		if (! this.missingBagItTolerant && bag.getBagItTxt() != null && ! bag.getBagConstants().getVersion().versionString.equals(bag.getBagItTxt().getVersion())) {
			result.setSuccess(false);
			result.addMessage(MessageFormat.format("Version is not {0}.", bag.getBagConstants().getVersion()));				
		}

		if (this.isCancelled()) return null;
		
		//All payload files are in data directory
		log.debug("Checking that all payload files in data directory");
		int total = bag.getPayload().size();
		int count = 0;
		for(BagFile bagFile : bag.getPayload()) {
			if (this.isCancelled()) return null;
			String filepath = bagFile.getFilepath();
			count++;
			this.progress("verifying payload file in data directory", filepath, count, total);
			log.trace(MessageFormat.format("Verifying payload {0} in data directory", filepath));
			if (! filepath.startsWith(bag.getBagConstants().getDataDirectory() + '/')) {
				result.setSuccess(false);
				result.addMessage(MessageFormat.format("Payload file {0} not in the {1} directory.", filepath, bag.getBagConstants().getDataDirectory()));
				log.warn(MessageFormat.format("Payload file {0} not in data directory", filepath));
			}
		}
		
		// Ensure no tag files are listed in the payload manifest.
		log.debug("Checking that no tag files are listed in payload manifests.");
		String payloadDirName = bag.getBagConstants().getDataDirectory();
		
		for (Manifest manifest : bag.getPayloadManifests())
		{
			if (this.isCancelled()) return null;

			this.progress("checking payload manifest for tag files", manifest.getFilepath());
			
			for (String path : manifest.keySet())
			{
				String normalizedPath = FilenameHelper.normalizePath(path);
				log.trace(format("Normalized path: {0} -> {1}", path, normalizedPath));
				
				if (!normalizedPath.startsWith(payloadDirName))
				{
					result.setSuccess(false);
					result.addMessage(format("Tag file is listed in payload manifest {0}: {1}", manifest.getFilepath(), path));
				}
			}
		}
		
		//Every payload BagFile in at least one manifest
		log.debug("Checking that every payload file in at least one manifest");
		total = bag.getPayload().size();
		log.trace(MessageFormat.format("{0} payload files to check", total));
		count = 0;
		for(BagFile bagFile : bag.getPayload()) {
			String filepath = bagFile.getFilepath();
			count++;
			this.progress("verifying payload file in at least one manifest", filepath, count, total);
			log.trace(MessageFormat.format("Verifying payload file {0} in at least one manifest", filepath));
			boolean inManifest = false;
			for(Manifest manifest : bag.getPayloadManifests()) {
				if (this.isCancelled()) return null;
				if (manifest.containsKey(filepath)) {
					inManifest = true;
					break;
				}
			}
			if (! inManifest) {
				result.setSuccess(false);
				result.addMessage(MessageFormat.format("Payload file {0} not found in any payload manifest.", filepath));														
				log.warn(MessageFormat.format("Payload file {0} not found in any payload manifest.", filepath));
			}
		}
		
		//Every payload file exists
		log.debug("Checking that every payload file exists");
		total = bag.getPayloadManifests().size();
		log.trace(MessageFormat.format("{0} payload manifests to check", total));
		count = 0;
		for(Manifest manifest : bag.getPayloadManifests()) {			
			count++;
			this.progress("verifying payload files in manifest exist", manifest.getFilepath(), count, total);
			this.checkManifest(manifest, bag, result);
			if (this.isCancelled()) return null;
		}

		//Every tag file exists
		log.debug("Checking that every tag file exists");
		total = bag.getTagManifests().size();
		log.trace(MessageFormat.format("{0} tag manifests to check", total));
		count = 0;
		for(Manifest manifest : bag.getTagManifests()) {
			count++;
			this.progress("verifying tag files in manifest exist", manifest.getFilepath(), count, total);
			this.checkManifest(manifest, bag, result);
			if (this.isCancelled()) return null;
		}
		
		//Additional checks if an existing Bag
		if (bag.getFile() != null) {
			DirNode bagDirNode;
			try {
				bagDirNode = FileSystemFactory.getDirNodeForBag(bag.getFile());
			} catch (UnknownFormatException e) {
				throw new RuntimeException(e);
			} catch (UnsupportedFormatException e) {
				throw new RuntimeException(e);
			}
			try {
				//FileObject bagFileObject = VFSHelper.getFileObjectForBag(bag.getFile());
				//Only directory is a data directory
				log.debug("Checking that only directory is data directory");
				if (! this.additionalDirectoriesInBagDirTolerant) {
					Collection<FileSystemNode> dirNodes = bagDirNode.listChildren(
							new AndFileSystemNodeFilter(
									new DirNodeFileSystemNodeFilter(),
									new IgnoringFileSystemNodeFilter(ignoreAdditionalDirectories, false)));
					for(FileSystemNode dirNode : dirNodes) {
						if (! bag.getBagConstants().getDataDirectory().equals(dirNode.getName())) {
							result.setSuccess(false);
							result.addMessage(MessageFormat.format("Directory {0} not allowed in bag_dir.", dirNode.getName()));								
						}
					}
				}
				
				log.debug("Checking that all payload files on disk included in bag");
				DirNode dataDirNode = bagDirNode.childDir(bag.getBagConstants().getDataDirectory());
				if (dataDirNode != null) {
					Collection<FileSystemNode> nodes = dataDirNode.listDescendants(new FileNodeFileSystemNodeFilter(), new IgnoringFileSystemNodeFilter(ignoreAdditionalDirectories, ignoreSymlinks));
					total = nodes.size();
					count = 0;
					for(FileSystemNode node : nodes) {
						if (this.isCancelled()) return null;
						FileNode fileNode = (FileNode)node;
						String filepath = FilenameHelper.removeBasePath(bagDirNode.getFilepath(), fileNode.getFilepath());
						count++;
						this.progress("verifying payload files on disk are in bag", filepath, count, total);
						log.trace(MessageFormat.format("Checking that payload file {0} is in bag", filepath));
						if (bag.getBagFile(filepath) == null) {
							result.setSuccess(false);
							String msg = MessageFormat.format("Bag has file {0} not found in manifest file.", filepath);
							result.addMessage(msg);
							log.warn(msg);
						}

					}
					
				}
			} finally {
				bagDirNode.getFileSystem().closeQuietly();
			}
			
		} else {
			log.debug("Not an existing bag");
		}
		log.info("Completed verification that bag is complete.");
		log.info("Note that this a verification of completeness, not validity. A bag may be complete without being valid, though a valid bag must be complete.");
		log.info("Result of verification that complete: " + result.toString());
		return result;

	}
	
	protected void checkManifest(Manifest manifest, Bag bag, BagVerifyResult result) {
		log.trace("Checking manifest " + manifest.getFilepath());
		int manifestTotal = manifest.keySet().size();
		int manifestCount = 0;
		for(String filepath : manifest.keySet()) {
			if (this.isCancelled()) return;
			manifestCount++;
			this.progress("verifying files in manifest exist", filepath, manifestCount, manifestTotal);
			log.trace(MessageFormat.format("Checking that file {0} in manifest {1} exists", filepath, manifest.getFilepath()));
			BagFile bagFile = bag.getBagFile(filepath);					
			if (bagFile == null || ! bagFile.exists())
			{
				result.setSuccess(false);
				result.addMissingOrInvalidFile(filepath);
				String message = MessageFormat.format("File {0} in manifest {1} missing from bag.", filepath, manifest.getFilepath());
				log.warn(message);
				result.addMessage(message);
			}
		}				
		
	}
	
}