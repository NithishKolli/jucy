package uc.files.filelist;


import helpers.FilterLowerBytes;
import helpers.GH;
import helpers.Observable;
import helpers.StatusObject;
import helpers.StatusObject.ChangeType;

import java.io.FileInputStream;
import java.io.InputStream;


import java.io.ByteArrayOutputStream;
import java.io.File;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.UnsupportedEncodingException;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;


import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;


import logger.LoggerFactory;

import org.apache.log4j.Logger;
import org.apache.tools.bzip2.CBZip2InputStream;
import org.apache.tools.bzip2.CBZip2OutputStream;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;


import org.osgi.framework.Bundle;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.helpers.AttributesImpl;


import uc.IUser;
import uc.PI;
import uc.crypto.HashValue;
import uc.crypto.TigerHashValue;




public class FileList extends Observable<StatusObject> implements Iterable<IFileListItem> {

	private static final Logger logger= LoggerFactory.make();
		
	protected HashValue cid 	= null;
	protected String generator	= "NONE";
	protected Exception readProblem;
	
	
	protected final IUser usr; //the owner
	
	protected volatile boolean completed = false;

	private volatile long sharedSize;
	protected final FileListFolder root = new FileListFolder(this);

	private final Map<HashValue,FileListFile> contents = new HashMap<HashValue,FileListFile>();
	

	private volatile byte[] filelistCashed;

	
	/**
	 * creates an empty FileList for the specified user..
	 * @param usr - the user that owns the FileList
	 */
	public FileList(IUser usr){
		this.usr = usr;
	}

	public boolean isCompleted(){
		return completed;
	}

	/**
	 * @return the root
	 */
	public FileListFolder getRoot() {
		return root;
	}
	
	
	
	/**
	 * provides an iterator over all FileList Items
	 * will first iterate over all Files then iterate over all
	 * Folders
	 */
	public Iterator<IFileListItem> iterator() {
		return new Iterator<IFileListItem>() {
			private final Iterator<FileListFile> itFile = root.iterator();
			private final Iterator<FileListFolder> itFolder = root.iterator2();
			private Iterator<? extends IFileListItem> current= itFile;
			
			public boolean hasNext() {
				if (!current.hasNext()) {
					current = itFolder;
				}
				return current.hasNext();
			}

			public IFileListItem next() {
				return current.next();
			}

			public void remove() {
				current.remove();
			}
			
		};
	}
	
	public Iterable<FileListFile> getFileIterable() {
		return root;
	}
	
	public Iterable<FileListFolder> getFolderIterable() {
		return new Iterable<FileListFolder>() {
			@Override
			public Iterator<FileListFolder> iterator() {
				return root.iterator2();
			}
		};
	}
	
	
	
	void addedOrRemoved(boolean added,IFileListItem item) {
		if (item.isFile()) {
			if (added) {
				addFileToSharesizeIfNotPresent((FileListFile)item);
			} else {
				removeFileFromShareSize((FileListFile)item);
			}
		}
		FileListFolder parent = item.getParent();
		notifyObservers(
				new StatusObject(item, 
						added?ChangeType.ADDED:ChangeType.REMOVED, 0, 
						parent));
		
		while (parent.getParent() != null) {
			notifyObservers(
					new StatusObject(parent, ChangeType.CHANGED, 0, 
							parent.getParent()));
			parent = parent.getParent();
		}
		filelistCashed = null;
	}

	/**
	 * @return the numberOfFiles
	 */
	public int getNumberOfFiles() {
		return root.getContainedFiles();
	}
	
	
	private void addFileToSharesizeIfNotPresent(FileListFile f) {
		FileListFile old = null;
		if ((old = contents.put(f.getTTHRoot(),f)) == null) {
			sharedSize += f.getSize();
		} else {
			contents.put(old.getTTHRoot(),old); 
		}
	}
	
	private void removeFileFromShareSize(FileListFile f) {
		FileListFile old= null;
		if ((old = contents.remove(f.getTTHRoot())) == f) {
			sharedSize -= f.getSize();
		} else {
			contents.put(old.getTTHRoot(),old); 
		}
	}
	

	/**
	 * @return the shared size
	 */
	public long getSharesize() {
		return sharedSize; 
	}
	
	/**
	 * reads in a filelist..
	 * @param path - the path where the filelist resides on the disc..
	 * @return true if successful .. false implies problem reading..
	 */
	public boolean readFilelist(File path) {
		InputStream in = null;
		try {
			in = new FileInputStream(path);
			if (!path.toString().endsWith(".xml")) {
				in = new PushbackInputStream(in);
				int b = in.read();
				if (b == 'B') {
					in.read();		//Z
					in = new CBZip2InputStream(in);
				} else {
					((PushbackInputStream)in).unread(b);
				}
			}
			in = new FilterLowerBytes(in); //needed to capture bad FileLists..
			
			readFilelist(in);
			
		} catch(Exception e){
			logger.warn(e,e);
			readProblem = e;
			return false;
		} finally {
			GH.close(in);
		}
		return true;
	}
	

	public void readFilelist(InputStream in)throws  IOException ,SAXException {
		InputStreamReader isr = null;
		try {

			setCompleted(false);
			//little workaround.. ignore bad input if Stream is directly used for parsing inserted it wouldn't
			isr = new InputStreamReader(in,"utf-8"); 

			SAXParserFactory saxFactory = SAXParserFactory.newInstance();
			
			try {
				SchemaFactory schFactory =  SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
				schFactory.setFeature("http://apache.org/xml/features/validation/schema-full-checking", false);
				Bundle bundle = Platform.getBundle(PI.PLUGIN_ID);
				Path path = new Path("XMLSchema/FilelistSchema.xml"); 

				URL url = FileLocator.find(bundle, path, Collections.EMPTY_MAP);

				Schema schema = schFactory.newSchema(url);
				saxFactory.setSchema(schema); 
			} catch(SAXNotRecognizedException snre) {
				logger.debug("Checking not supported "+snre, snre);
			}

			SAXParser saxParser = saxFactory.newSAXParser();

			saxParser.parse(new InputSource(isr), new FileListParser(this));

		} catch(ParserConfigurationException pce){
			logger.error(pce,pce);
		}
		
		//calcSharesizeAndBuildTTHMap();
	}
	
	/**
	 * 
	 * @param out
	 * @param path - "/" usually  otherwise the path of the base from where Serialization should start..
	 * @param recursive
	 * @throws UnsupportedEncodingException
	 * @throws IOException
	 * @throws IllegalArgumentException - if the path does no exist
	 */
	private void writeFilelist(OutputStream out,String path,boolean recursive) throws UnsupportedEncodingException , IOException , IllegalArgumentException {

		try {
			StreamResult streamResult = new StreamResult(out);
			SAXTransformerFactory tf = (SAXTransformerFactory) SAXTransformerFactory
				.newInstance();
			// SAX2.0 ContentHandler.
			TransformerHandler hd = tf.newTransformerHandler();
			Transformer serializer = hd.getTransformer();
			serializer.setOutputProperty(OutputKeys.ENCODING, "utf-8");
			//serializer.setOutputProperty(OutputKeys.INDENT, "yes");
			serializer.setOutputProperty(OutputKeys.VERSION, "1.0");
			serializer.setOutputProperty(OutputKeys.STANDALONE, "yes");
			hd.setResult(streamResult);
			hd.startDocument();
			AttributesImpl atts = new AttributesImpl();
			// USERS tag.
			atts.addAttribute("", "", "Version", "CDATA", "1");
			if (cid != null) {
				atts.addAttribute("", "", "CID", "CDATA",  cid.toString());
			}
			atts.addAttribute("", "", "Base", "CDATA", path);
			atts.addAttribute("", "", "Generator", "CDATA", generator);
		
			hd.startElement("", "", "FileListing", atts);
			
			
			FileListFolder parentFolder = root.getByPath(path.replace('/', File.separatorChar),true);
			
			if (parentFolder != null) {
				parentFolder.writeToXML(hd, atts,recursive,true,true);	
			} else {
				logger.info("Path requested, but not found: "+path);
			}

			hd.endElement("", "", "FileListing");
			hd.endDocument();
		
			out.flush();
		} catch(SAXException sax) {
			throw new IOException(sax);
		} catch (TransformerConfigurationException pce) {
			throw new IOException(pce);
		}
	}
	
	
	
	
	/**
	 * writes filelist to a byte[] array
	 * @param path subpath within the filelist / or null for whole filelist
	 * @return
	 */
	public byte[] writeFileList(String path, boolean recursive,boolean bz2Compressed)  {
		if (path == null) {
			path = "/";
		}
		logger.debug("("+(path == null? "null":path)+")" );
	//	long start = System.currentTimeMillis();
		boolean cacheable = path.equals("/") & bz2Compressed & recursive ;
	
		byte[] fileList = null;

		if (cacheable && filelistCashed != null) {
			return filelistCashed;
		}

		ByteArrayOutputStream baos = null;
		OutputStream out = null;
		try {
			baos = new  ByteArrayOutputStream();
			out = baos;
			if (bz2Compressed) {
				baos.write('B');
				baos.write('Z');
				out 	= new CBZip2OutputStream(baos);
			}
			writeFilelist(out,path,recursive);

		} catch(IOException ioe) {
			logger.warn(ioe,ioe); //this should never happen... everything is done in memory..
		} finally {
			GH.close(out);
		}
		fileList = baos.toByteArray();
	//	long end = System.currentTimeMillis();
	//	logger.info("Time Filelist: " + (end-start)+" size: "+fileList.length+" recursive:"+recursive+" bz2: "+bz2Compressed+" path: "+path);
		if (cacheable) {
			filelistCashed = fileList;
		}


		return fileList;
	}
//	/**
//	 * 
//	 * @param file - the target where the filelist should be written to..
//	 * @throws IOException - if some error occurs..
//	 * 
//	 */
//	public void writeFilelist(File file) throws IOException {
//		FileOutputStream fos = null;
//		try {
//			fos	= new FileOutputStream(file);
//			fos.write(writeFileList("/", true,true));
//			fos.flush();
//		} finally {
//			GH.close(fos);
//		}
//	}
	
	/**
	 * deletes all FileLists in the FileList directory
	 */
	public static void deleteFilelists() {
		File f = PI.getFileListPath();
		if (f.isDirectory()) {
			for (File filelist:f.listFiles()) {
				if (filelist.isFile() && filelist.getName().endsWith(".xml.bz2")) {
					if (!filelist.delete()) {
						filelist.deleteOnExit();
					}
				}
			}
		}
	}
	
//	/**
//	 * adds the owner of this FileList to all files
//	 * that are in DownloadQueue as well as in this FileList..
//	 */
//	public void match(DownloadQueue dq) {
//		dq.match(this);
//	}
	
	/**
	 * searches for a file by its hashvalue
	 * 
	 * @param value - TTH of the searched file
	 * @return null if not found otherwise the found file
	 */
	public FileListFile search(HashValue value) {
		return contents.get(value);
	}
	
	/**
	 * 
	 * @param onSearch - a regexp used to search in names of files and folders
	 * @return the results containing of a list with folders and files
	 */
	public List<IFileListItem> search(Pattern onSearch) {
		List<IFileListItem> results = new ArrayList<IFileListItem>();
		root.search(onSearch, results);
		return results;
	}
	
	/**
	 * 
	 * @param onSearch - a substring to match
	 * @return all files and folders which names match the provided substring
	 */
	public List<IFileListItem> search(String onSearch) {
		List<IFileListItem> found = search(Pattern.compile(Pattern.quote(onSearch)));
		if (onSearch.matches(TigerHashValue.TTHREGEX)) {
			FileListFile f = search(HashValue.createHash(onSearch));
			if (f != null) {
				found.add(f);
			}
		}
		return found; 
	}
	
	

	/**
	 * @return the usr
	 */
	public IUser getUsr() {
		return usr;
	}

	/**
	 * @return the cID
	 */
	public HashValue getCID() {
		return cid;
	}

	/**
	 * @param cid the cID to set
	 */
	public void setCID(HashValue cid) {
		this.cid = cid;
	}

	/**
	 * @return the generator
	 */
	public String getGenerator() {
		return generator;
	}

	/**
	 * @param generator the generator to set
	 */
	public void setGenerator(String generator) {
		this.generator = generator;
	}

	/**
	 * @param completed the completed to set
	 */
	public void setCompleted(boolean completed) {
		this.completed = completed;
	}
	
	public boolean deepEquals(FileList f) {
		return usr.equals(f.usr) && root.deepEquals(f.root);
	}
	

	

}