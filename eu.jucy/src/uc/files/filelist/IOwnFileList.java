package uc.files.filelist;

import java.io.File;
import java.util.Set;

import uc.crypto.HashValue;
import uc.files.filelist.OwnFileList.SearchParameter;

public interface IOwnFileList {

	/** <sizerestricted>?<ismaxsize>?<size>?<datatype>?<searchpattern>
	 * 
	 * @param key - the searchwords
	 * @param fileendings - which fileEndings the file must match. empty if all allowed
	 * @param type
	 * @param active
	 * @return
	 */
	Set<IFileListItem> search(SearchParameter sp);

	/**
	 * 
	 * @param tth - tth of the searched file..
	 * @return null if nothing found, or the found File.
	 */
	FileListFile search(HashValue tth);

	/**
	 * Get a java.io.File from a TTH
	 * @param tth - the hashvalue of the file
	 * @return the path to the local filesystem holding the file , null if not found
	 * 
	 */
	File getFile(HashValue tth);

	/**
	 * Get a java.io.file from a filelist File
	 * @param file a Filelist file
	 * @return a real java.io.File
	 */
	File getFile(FileListFile file);

	long getSharesize();

	int getNumberOfFiles();
	
	void refresh(boolean wait);
	
	void initialise();
	
	FileList getFileList();

}