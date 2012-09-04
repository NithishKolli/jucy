package uc.files.filelist;

import helpers.GH;
import helpers.LevensteinDistance;

import java.io.File;
import java.io.IOException;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import javax.xml.transform.sax.TransformerHandler;

import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import uc.IUser;
import uc.crypto.HashValue;
import uc.files.AbstractDownloadable.AbstractDownloadableFolder;
import uc.files.IDownloadable.IDownloadableFolder;
import uc.files.downloadqueue.AbstractDownloadQueueEntry;




/**
 * a folder in  a FileList
 * containing other FilelistFiles/folders
 * 
 * @author Quicksilver
 *
 */
public class FileListFolder extends AbstractDownloadableFolder implements  Iterable<FileListFile> , IDownloadableFolder, IFileListItem  {

	

	private final FileList fileList; 
	private long containedSize	= 0;
	private int containedFiles 	= 0;
	private final FileListFolder parent;
	private final String foldername;
	private final List<FileListFolder> subfolders = new CopyOnWriteArrayList<FileListFolder>();
	private final List<FileListFile> files = new CopyOnWriteArrayList<FileListFile>();
	
	/**
	 * 
	 * constructor only used by root element
	 */
	FileListFolder(FileList rootElementConstructor) {
		this(rootElementConstructor,null,"");
	}
	
	/**
	 * normal constructor
	 * @param parent - parent folder
	 * @param foldername - name of folder
	 */
	public FileListFolder(FileListFolder parent, String foldername ) {
		this(parent.getFilelist(),parent,foldername);
	}
	
	private FileListFolder(FileList f, FileListFolder parent, String foldername ) {
		this.fileList	= f;
		this.parent		= parent;
		this.foldername	= foldername;
		if (parent != null) {
			parent.addChild(this);
		}
	}


	public FileListFolder getChildPerName(String foldernameOfTheChild) {
		for (FileListFolder f:subfolders) {
			if (f.foldername.equals(foldernameOfTheChild)) {
				return f;
			}
		}
		return null;
	}
	
	public FileListFolder getClosestChildPerName(String foldernameOfTheChild) {
		FileListFolder ff = getChildPerName(foldernameOfTheChild);
		if (ff == null) {
			ArrayList<String> foldernames = new ArrayList<String>();
			for (FileListFolder f:subfolders) {
				foldernames.add(f.foldername);
			}
			String closestName = LevensteinDistance.getClosest(foldernames, foldernameOfTheChild);
			return getChildPerName(closestName);
		}
		return ff;
	}
	
	

	public FileListFile getFilePerName(String filenameOfTheChild){
		for (FileListFile f:files) {
			if (f.getName().equals(filenameOfTheChild)) {
				return f;
			}
		}
		return null;
	}


	/**
	 * 
	 * @param size
	 * @param add false for removeal
	 */
	private void addSizeToFolderparent(long size,boolean add){
		containedSize += add? size:-size;
		containedFiles+= add?1:-1;
		if (parent != null) {
			parent.addSizeToFolderparent(size,add);
		}
	}
	
	@Override
	public boolean isOriginal() {
		return true;
	}

	/**
	 * adds a new filelistfile to this folder.. 
	 * only called by filelistfile
	 * @param a - the file that should be added to this folder
	 */
	void addChild(FileListFile a) {
		int found = Collections.binarySearch(files, a);
		if (found < 0) {
			files.add(-(found+1) , a);
			addSizeToFolderparent(a.getSize(),true);
			fileList.addedOrRemoved(true, a);
		}
	}
		
		/**
		 * 
		 * @param a - a Folder that should eb added to thisfolder
		 */
		void addChild(FileListFolder a) {
			int found = Collections.binarySearch(subfolders, a);
			if (found < 0) {
				subfolders.add(-(found+1) , a);
				fileList.addedOrRemoved(true, a);
			}
		}
		
		void removeChild(FileListFile a) {
			int found = Collections.binarySearch(files, a);
			if (found >= 0) {
				files.remove(found);
				addSizeToFolderparent(a.getSize(),false);
				fileList.addedOrRemoved(false, a);
			}
		}
		
		void removeChild(FileListFolder a) {
			int found = Collections.binarySearch(subfolders, a);
			if (found >= 0) {
				a.clear();
				subfolders.remove(found);
				fileList.addedOrRemoved(false, a);
			}
		}
		
		/**
		 * removes  all children from the folder -> clears it
		 */
		private void clear() {
			for (FileListFile f:files) {
				removeChild(f);
			}
			for (FileListFolder ff:subfolders) {
				removeChild(ff);
			}
		}
		
		
		public void removeChild(String filename) {
			for (FileListFile  f: files) {
				if (f.getName().equals(filename)) {
					removeChild(f);
				}
			}
		}
		
		public void removeFolder(String foldername) {
			for (FileListFolder f: subfolders) {
				if (f.getName().equals(foldername)) {
					removeChild(f);
				}
			}
		}
		
		public void remove(String fileOrFoldername){
			removeChild(fileOrFoldername);
			removeFolder(fileOrFoldername);
		}
		
		public String getPath() {
			if (parent != null) {
				return  parent.getPath() +foldername+File.separator;
			} else {
				return "";
			}
		}
		
		/**
		 * the user for IDownloadable ..
		 * in this case the filelist owner
		 */
		public IUser getUser() {
			return fileList.getUsr();
		}
		
		/**
		 * uses the pattern to find results
		 * @param onSearch
		 * @param results
		 */
		public void search(Pattern onSearch, List<IFileListItem> results) {
			
			for (FileListFolder folder: subfolders) {
				if (onSearch.matcher(folder.getName()).find()) {
					results.add(folder);
				}
				folder.search(onSearch, results);
			}
			
			for (FileListFile file : files) {
				if (onSearch.matcher(file.getName()).find()) {
					results.add(file);
				}
			}
			
		}
		
		
		@Override
		public HashValue getTTHRoot() {
			throw new UnsupportedOperationException("Folders do not have hashValues"); 
		}

		
		public List<IFileListItem> getChildren() {
			List<IFileListItem> children = new ArrayList<IFileListItem>();
			children.addAll(subfolders);
			children.addAll(files);

			return children;
		}
		
		public boolean hasSubfolders() {
			return !subfolders.isEmpty() ;
		}
		
		
		/**
		 * recursively writes this FilelistFolder and its children
		 * to a writer...
		 * used to build XML FileLists  
		 * 
		 * @param out - where to write to
		 * @param recursive - if lower levels should be written out
		 * @param writeout - if this is not written out only the name of the folder should be printed
		 * but no contents
		 * 
		 * @throws IOException - errors that occur on writing to out are thrown
		 */
		public void writeToXML(TransformerHandler hd,AttributesImpl atts,boolean recursive,boolean isBase,boolean writeout) throws  SAXException {
			atts.clear();
			
			//root doesn't write itself.. and any empty directory doesn't write itself
			boolean writeSelf = !isBase && !(subfolders.isEmpty() && files.isEmpty()); 
			
			if (writeSelf) {
				atts.addAttribute("", "", "Name", "CDATA", foldername);
				if (!recursive && !writeout && !(subfolders.isEmpty() && files.isEmpty() ) ) {
					atts.addAttribute("", "", "Incomplete", "CDATA", "1");
				}
				
				hd.startElement("", "", "Directory", atts);
			}
			
			if (writeout) {
				
				for (FileListFolder f: subfolders) {
					f.writeToXML(hd,atts,recursive,false,recursive?true:false);
				}
				
				for (FileListFile f: files) {
					f.writeToXML( hd,atts );
				}
				
			}
			
			if (writeSelf) {
				hd.endElement("", "", "Directory");
			}
		}
		
		
		
		/**
		 * @return the foldername
		 */
		public String getName() {
			return foldername;
		}
		
		/**
		 * @return the parent
		 */
		public FileListFolder getParent() {
			return parent;
		}

		/**
		 * @return the filelist
		 */
		public FileList getFilelist() {
			return fileList;
		}

		/**
		 * @return the files
		 */
		public List<FileListFile> getFiles() {
			return files;
		}

		/**
		 * @return the subfolders
		 */
		public List<FileListFolder> getSubfolders() {
			return subfolders;
		}

		/**
		 * @return the containedFiles
		 */
		public int getContainedFiles() {
			return containedFiles;
		}

		/**
		 * @return the containedSize
		 */
		public long getSize() {
			return containedSize;
		}
		
		

		
		@Override
		public AbstractDownloadQueueEntry download(File target) {
			
			for (FileListFolder folder : subfolders) {
				folder.download(new File(target,folder.getName()));
			}
			
			
			for (FileListFile file:files) {
				file.download(new File(target,file.getName()));
			}
			

			
			return null;
		}
		
		public FileListFolder getByPath(String path) {
			return getByPath(path, false);
		}
		
		/**
		 * FileList folder by path ..
		 * File.separator char is used to separate paths..
		 * 
		 * @param path 
		 * @param allowApproximation / take smallest  levensteinsdistance if not found
		 * @return
		 */
		public FileListFolder getByPath(String path,boolean allowApproximation) {
			int i 	= path.indexOf(File.separatorChar);
			if (i == 0) {
				path= path.substring(1);
				i 	= path.indexOf(File.separatorChar);
			}
			if (GH.isEmpty(path)) {
				return this;
			} 
			String name;
			if (i == -1) {
				name = path;
			} else {
				name = path.substring(0, i);
			}
			FileListFolder next =  allowApproximation ? getClosestChildPerName(name) :getChildPerName(name);
			
			if (next != null) {
				return next.getByPath(path.substring(name.length()),allowApproximation);
			} else {
				return null;
			}
		}
		


		public boolean deepEquals(FileListFolder obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			final FileListFolder other =  obj;
			if (containedFiles != other.containedFiles)
				return false;
			if (containedSize != other.containedSize)
				return false;
			if (files == null) {
				if (other.files != null)
					return false;
			} else if (!files.equals(other.files))
				return false;
			if (foldername == null) {
				if (other.foldername != null)
					return false;
			} else if (!foldername.equals(other.foldername))
				return false;
			if (parent == null) {
				if (other.parent != null)
					return false;
			} else if (!parent.equals(other.parent))
				return false;
			if (subfolders == null) {
				if (other.subfolders != null)
					return false;
			} else if (!subfolders.equals(other.subfolders))
				return false;
			return true;
		}
		
		

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result
					+ ((foldername == null) ? 0 : foldername.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			FileListFolder other = (FileListFolder) obj;
			if (foldername == null) {
				if (other.foldername != null)
					return false;
			} else if (!foldername.equals(other.foldername))
				return false;
			if (parent == null) {
				if (other.parent != null)
					return false;
			} else if (!parent.equals(other.parent))
				return false;
			return true;
		}



		//		-----------------------Start Enumerator .. ..
		private class FLIterator implements Iterator<FileListFile> {
			private Iterator<FileListFolder> e1;
			private Iterator<FileListFile>   e2; 
			private Iterator<FileListFile> current;

			public FLIterator() {

				e1 = subfolders.iterator();
				e2 = files.iterator();
				if (e1.hasNext()) {
					current = e1.next().iterator();
				}
			}
			
			public boolean hasNext() {
				if (e2.hasNext()) {
					return true;	
				} else if (current == null) {
					return false;
				} else if (current.hasNext()) {
					return true;
				} else if (e1.hasNext()) {
					current = e1.next().iterator();
					return hasNext();
				} else {
					current = null;
					return false;
				}
			}
			
			public FileListFile next() {
				if (e2.hasNext()) {
					return e2.next();
				} else if (current == null) {
					return null;
				} else if (current.hasNext()) {
					return current.next();
				} else if(e1.hasNext()) {
					current= e1.next().iterator();
					return next();
				} else {
					current = null;
					return null;
				}
			}
			
			public void remove(){
				throw new UnsupportedOperationException();
			}	
		}

		/**
		 * iterates over all FileListFiles contained
		 * in this Folder or its subfolders recursively..
		 */
		public Iterator<FileListFile> iterator() { 
			return new FLIterator();
		}
		
		private class FolderIterator implements Iterator<FileListFolder> {

			private Iterator<FileListFolder> first =  subfolders.iterator();
			private Iterator<FileListFolder> second =  subfolders.iterator();
			
			private Iterator<FileListFolder> current;
			
			public FolderIterator() {
				if (second.hasNext()) {
					current = second.next().iterator2();
				}
			}
			
			public boolean hasNext() {
				if (current == null) {
					return false;
				} else if (first.hasNext()) {
					return true;
				} else if (current.hasNext()) {
					return true;
				} else if (second.hasNext()) {
					current = second.next().iterator2();
					return hasNext();
				} else {
					current = null;
					return false;
				}
			}

			public FileListFolder next() {
				if (current == null) {
					return null;
				} else if (first.hasNext()) {
					return first.next();
				} else if(current.hasNext()) {
					return current.next();
				} else if (second.hasNext()) {
					current = second.next().iterator2();
					return next();
				} else {
					current = null;
					return null;
				}
			}

			public void remove() {
				throw new UnsupportedOperationException();
			}
			
		}
		
		/**
		 * gives an iterator that iterates over all contained 
		 * subfolders recursively
		 * @return
		 */
		public Iterator<FileListFolder> iterator2() {
			return new FolderIterator();
		}
		
		
		//-----------------------End Enumerator ..
		
		
	}

