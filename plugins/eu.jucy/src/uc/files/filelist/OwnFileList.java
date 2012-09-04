package uc.files.filelist;




import helpers.GH;
import helpers.ISearchMap;
import helpers.PreferenceChangedAdapter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

import javax.xml.transform.sax.TransformerHandler;



import logger.LoggerFactory;



import org.apache.log4j.Logger;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;






import uc.DCClient;
import uc.FavHub;
import uc.IUser;
import uc.InfoChange;
import uc.LanguageKeys;
import uc.PI;
import uc.FavFolders.SharedDir;
import uc.crypto.HashValue;
import uc.crypto.IHashEngine;
import uc.crypto.InterleaveHashes;
import uc.crypto.IHashEngine.IHashedFileListener;
import uc.database.HashedFile;
import uc.database.IDatabase;
import uc.files.filelist.FileListMapping.FileFilter;
import uc.user.User;



/**
 * 
 * special FileList with facility for searching
 * @author Quicksilver
 *
 */
public class OwnFileList implements IOwnFileList  {
	
	private static final Logger logger = LoggerFactory.make();
	

	private static final int REFRESH_CHECK_MINUTES = 5;
	
	private final DCClient dcc;
	private volatile ISearchMap<IFileListItem> filelistmap = 
		new InvertedIndex<IFileListItem>(new FileListMapping());
	
	private final TextIndexer pdfIndex;

	private ScheduledFuture<?> refresher;
	
	private final Lock lock = new ReentrantLock();
	private final Condition fileListInitialized = lock.newCondition();
	private boolean initialized = false;
	

	
	private final List<TopFolder> topFolders =   new CopyOnWriteArrayList<TopFolder>(); 
	
	/**
	 * maps shared dirs to whether they were online the last time...
	 * -> dirs that suddenly become unavailable must be cleared from the FileFist
	 */
	private final Map<SharedDir,Boolean> lastOnlineDirs = 
		Collections.synchronizedMap(new HashMap<SharedDir,Boolean>());
	
	private final User filelistSelf;
	/**
	 * our own FileList
	 */
	private volatile FileList fileList;
	
	

	private TmpSharedTopFolder hiddenTop;
	
	private final IDatabase database;
	private final IHashEngine hashEngine;



	/**
	 * if set to true the FileList is being refreshed -> no new refresh is started..
	 */
	private final Semaphore refresh = new Semaphore(1,false);
	
	/**
	 * variable to store if the FileList in ram is the same as the one
	 * on the HDD
	 *  or lately if some adding failed..
	 * 
	 */
	private volatile boolean filelistNeedsRefresh = false;
	
	private final PreferenceChangedAdapter pca;
	
	public OwnFileList(User self,IDatabase database,IHashEngine hashEngine,DCClient dcclient) {
		this.database = database;
		this.hashEngine = hashEngine;
		dcc = dcclient;
		this.filelistSelf = self;
		fileList = new FileList(self);
		//specialHidden = new FileList(self);
		hiddenTop =  new TmpSharedTopFolder(fileList);
		TextIndexer pdfI = null;
		try {
			pdfI = new TextIndexer();
		} catch(IOException e) {
			logger.warn(e,e);
		}
		pdfIndex = pdfI;
		
		pca = new PreferenceChangedAdapter(PI.get(),PI.sharedDirs2) {
			@Override
			public void preferenceChanged(String preference, String oldValue,String newValue) {
				dcc.getSchedulerDir().schedule(new Runnable() {
					public void run() {
						refresh(false);
					}
				} , 500, TimeUnit.MILLISECONDS);	
			}
		};
	}
	
	
	
	
	/**
	 * 
	 * 
	 * @return true if the SharedDirs changed since last call of this method
	 */
	private boolean checkSharedDirs() {
		Map<SharedDir,Boolean> dirs = new HashMap<SharedDir,Boolean>();
		for (TopFolder dir: topFolders) {
			for (SharedDir sd:dir.sharedDirs) {
				dirs.put(sd, sd.isOnline());
			}
			
		}
		if (dirs.equals(lastOnlineDirs)) {
			return false;
		} else {
			lastOnlineDirs.clear();
			lastOnlineDirs.putAll(dirs);
			return true;
		}
	}
	
	/**
	 * simply loads the dirs that are to be shared..
	 *
	 */
	private void loadSharedDirs(List<TopFolder> topLevelFolders,FileList fileList) {
		
		List<SharedDir> loadeddirs = dcc.getFavFolders().getSharedDirs();
		
		//add new dirs
		for (SharedDir dir: loadeddirs ) {
			TopFolder present = null;
			for (TopFolder tf:topLevelFolders) {
				if (tf.getName().equals(dir.getName())) {
					present = tf;
					break;
				}
			}
			if ( present == null) {
				TopFolder f = 
					new TopFolder(fileList, dir);
				topLevelFolders.add(f);
			} else {
				present.sharedDirs.add(dir);
			}
		}
	}


	
	/**
	 * called on creating for faster 
	 */
	public void start() {
		refresh(true);
		//updates the FileList every 60 Minutes or every 5 Minutes if hashing..
		refresher = dcc.getSchedulerDir().scheduleAtFixedRate(new Runnable(){
			int counter = 0;
			public void run() {
				if (++counter * REFRESH_CHECK_MINUTES >= PI.getInt(PI.filelistRefreshInterval)  || 
						filelistNeedsRefresh || checkSharedDirs()) {
					refresh(false);
					counter = 0;
				}
			}
		}, REFRESH_CHECK_MINUTES , REFRESH_CHECK_MINUTES , TimeUnit.MINUTES);
		
		dcc.notifyChangedInfo(InfoChange.Sharesize);
		
		lock.lock();
		try {
			initialized = true;
			fileListInitialized.signalAll();
		} finally {
			lock.unlock();
		}
		
		if (PI.getBoolean(PI.fullTextSearch)) {
			pdfIndex.init(this);
		}
		pca.reregister();
		

		
	}
	
	public void stop() {
		PI.put(PI.lastFilelistSize, getSharesize());
		PI.put(PI.lastNumberOfFiles, getNumberOfFiles());
		
		
		if (pdfIndex != null) {
			pdfIndex.stop();
		}
		
		if (refresher != null) {
			refresher.cancel(false);
		}
		pca.dispose();
	}
	
	/**
	 * 
	 * @param wait - if the method should block until the refresh is done
	 */
	public void refresh(boolean wait) {
		RefreshJob rj = new RefreshJob();
		rj.schedule();
		if (wait) {
			try {
				rj.join();
			} catch(InterruptedException is) {}
		}
	}
	
	/**
	 * refreshes the FileList  
	 * goes recursively through all shared dirs..
	 * and checks if hashes are up to date... or if the file needs to be hashed
	 */
	private void refresh(IProgressMonitor monitor) {	
		if (refresh.tryAcquire()) {
			logger.debug(LanguageKeys.StartedRefreshingTheFilelist);
			try {
				FileList newFilelist  = new FileList(filelistSelf);
				newFilelist.setGenerator(DCClient.LONGVERSION);
				newFilelist.setCID(filelistSelf.getCID());
				
				//if for example less files are shared this will help that no useless files are hashed
				
				hashEngine.clearFileJobs(); 
				List<TopFolder> topLevelFolders = new ArrayList<TopFolder>();
				loadSharedDirs(topLevelFolders,newFilelist);
		
				
				
				monitor.beginTask(LanguageKeys.StartedRefreshingTheFilelist,topLevelFolders.size() +1);
				
				ISearchMap<IFileListItem> filelistmap = new InvertedIndex<IFileListItem>(new FileListMapping());
				buildFilelist(newFilelist,topLevelFolders,filelistmap,monitor);
				TmpSharedTopFolder newHiddenTop = hiddenTop.copyTo(newFilelist); 
				if (monitor.isCanceled()) {
					return;
				}
				if (!newFilelist.deepEquals(fileList)) {
					replaceFilelist(newFilelist,topLevelFolders,filelistmap,newHiddenTop);
					logger.info("actually replaced Filelist!");
				}
				monitor.worked(1);
				
				filelistNeedsRefresh = false; 
				dcc.logEvent( LanguageKeys.FinishedFilelistRefresh );
				
			} finally {
				monitor.done();
				refresh.release();
			}
			
			checkSharedDirs(); //finally updating the SharedDirs..
			
		} else {
			dcc.logEvent( LanguageKeys.FilelistRefreshAlreadyInProgress);
		}
	}
	
	private void replaceFilelist(FileList currentFilelist,List<TopFolder> topLevelFolders,ISearchMap<IFileListItem> filelistmap,TmpSharedTopFolder newHiddenTop) {	
		this.filelistmap = filelistmap;
		User self = (User)fileList.getUsr();
		hiddenTop = newHiddenTop; //the files added by hand won't be in the list.. if they are not copied..
		
		self.setFilelistDescriptor(new FileListDescriptor(self,currentFilelist));
		self.setShared(currentFilelist.getSharesize());
		
		topFolders.clear();
		topFolders.addAll(topLevelFolders);
		
		FileList oldFilelist 	= fileList; 
		fileList 				= currentFilelist;
		
		
		if (oldFilelist.getSharesize() != fileList.getSharesize()||oldFilelist.getNumberOfFiles() != fileList.getNumberOfFiles()) { //equals for hub..
			dcc.notifyChangedInfo(InfoChange.Sharesize);
		}
		
	}
	
	
	private void buildFilelist(FileList newFilelist,List<TopFolder> topLevelFolders,ISearchMap<IFileListItem> filelistmap,IProgressMonitor monitor) {
		Map<File,HashedFile> hashedFiles = dcc.getDatabase().getAllHashedFiles();
		logger.debug("in refreshing own filelist: found "+hashedFiles.size()+" hashed files");
		
		Pattern exclude = Pattern.compile(PI.get(PI.excludedFiles));
		Pattern include = Pattern.compile(PI.get(PI.includeFiles));
		
		//recursively go through all shared dirs..
		for (TopFolder folder : topLevelFolders) {
			for (SharedDir dir:folder.getSharedDirs()) {
				if (dir.getDirectory().isDirectory()) {
					long contained = rekBuildFilelist(dir.getDirectory()
						, folder
						, newFilelist
						, PI.getBoolean(PI.shareHiddenFiles)
						, hashedFiles,exclude,include,filelistmap);
					dir.setLastShared(contained);
				}
			}
//			for (SharedDir dir:folder.getSharedDirs()) {
//				dir.setLastShared( folder.getContainedSize());
//			}
			
			monitor.worked(1);
			if (monitor.isCanceled()) {
				break;
			}
		}
		
	}
	
	/**
	 * goes recursively through the file system and checks if a file needs to be hashed..
	 * 
	 * @param folder - a folder on the HDD
	 * @param listfolder - the corresponding FilelistFolder
	 * @return contained size
	 */
	private long rekBuildFilelist(File file, FileListFolder listfolder, FileList current, boolean shareHidden, 
			Map<File,HashedFile> hashedFiles, Pattern exclude,Pattern include,ISearchMap<IFileListItem> filelistmap) {
		
		logger.debug("refreshing folder: "+file);

		long contained = 0;
		for (File child : GH.getFiles(file, !shareHidden) ) {
			if (child.isDirectory()) {
				FileListFolder subfolder = new FileListFolder( listfolder , child.getName());
				contained += rekBuildFilelist(child, subfolder,current,shareHidden,hashedFiles ,exclude, include,filelistmap);
			} else if (child.isFile() ) {
				Matcher inc = include.matcher(child.getName());
				if (inc.find()) {
					Matcher exc = exclude.matcher(child.getName());
					if (!exc.find()) {
						FileListFile ff = addFile(child,listfolder, hashedFiles,filelistmap,false);
						if (ff != null) {
							contained+= ff.getSize();
						}
					} 
				}
			}
		}
		return contained;
	}
	

	
	private FileListFile addFile(final File file, final FileListFolder parent, Map<File,HashedFile> hashedFiles,final ISearchMap<IFileListItem> filelistmap,boolean highPriority) {
		HashedFile hashedFile =  hashedFiles.get(file);
		
		if (hashedFile == null || !hashedFile.isValid()) {
			logger.debug("found file needs hashing: "+(file ==null?"null":file.toString()) );
			
			hashEngine.hashFile(file,false, new IHashedFileListener() {
				public void hashedFile(HashedFile hf, InterleaveHashes ilh) {
					database.addOrUpdateFile(hf, ilh);
					addFile(hf.getPath(),parent,Collections.singletonMap(hf.getPath(), hf),filelistmap,false);
					if (!filelistNeedsRefresh && parent.getFilelist() != fileList) {
						filelistNeedsRefresh = true;  
					}
				}
			});	
		} else {
			//logger.debug("adding file to FileList:"+file);
			//we have a current hash.. so we can create the file with it
			HashValue tthRoot = hashedFile.getTTHRoot();
			if (tthRoot != null) {
				FileListFile flf = new FileListFile(parent, file.getName(), file.length(), tthRoot);
				filelistmap.put( flf);
				return flf;
			} else if (Platform.inDevelopmentMode()) {
				logger.warn("tth is null "+file.getPath());
			}
		}
		return null;
	}
	
	/**
	 * 
	 * @param file top Folder o search for this file
	 * @return matching TopFolder if found.. else null
	 */
	private SharedDir getTopdir(File file) {
		String filePath = file.getPath();
		for (TopFolder dir: topFolders) {
			for (SharedDir sd:dir.sharedDirs) {
				String dirPath = sd.getDirectory().getPath()+File.separator;
				if (filePath.startsWith(dirPath)) {
					return sd;
				}
			}
		}
		return null;
	}
	
	private TopFolder getTopFolder(SharedDir sda) {
		for (TopFolder dir: topFolders) {
			for (SharedDir sd:dir.sharedDirs) {
				if (sd.equals(sda)) {
					return dir;
				}
			}
		}
		return null;
	}
	
	
	public void immediatelyAddFile(File file) {
		immediatelyAddFile(file,false,null,new AddedFile());
	}
	
	public void immediatelyAddFile(File file,final boolean force,final IUser restrictForUser,final AddedFile callback) {
	//	logger.info("immediately adding file: "+file);
		
		HashedFile hf = dcc.getDatabase().getHashedFile(file);
		SharedDir sd = getTopdir(file);
		TopFolder tf = getTopFolder(sd);
		
		if (hf == null || !hf.isValid()) {
			if (force || sd != null) {
				hashEngine.hashFile(file,true, new IHashedFileListener() {
					public void hashedFile(HashedFile hashedFile, InterleaveHashes ilh) {
						database.addOrUpdateFile(hashedFile, ilh);
						immediatelyAddFile(hashedFile.getPath(),force,restrictForUser,callback);
					}
				});
			}
			return;
		}
		
		FileListFile addedFile = fileList.search(hf.getTTHRoot());
		if (addedFile instanceof SpecialFileListFile) { // if already present.. change permission
			((SpecialFileListFile) addedFile).add(restrictForUser);
		}
		// if not present but matches some top folder..
		if (addedFile == null && sd != null) {
			String dirPath = sd.getDirectory().getPath() + File.separator;
			String parentPath = file.getParentFile().getPath();
			FileListFolder current = tf;
			if (parentPath.length() > dirPath.length()) {
				String[] pathLeft = parentPath.substring(dirPath.length()).split(Pattern.quote(File.separator));
				
				for (String s:pathLeft) {
					FileListFolder next = current.getChildPerName(s);
					if (next == null) {
						next = new FileListFolder(current, s);
					}
					current = next;
				}
			}
			FileListFile present = current.getFilePerName(file.getName()); //due to concurrency could now be present...
			if (present == null) {
				addedFile = addFile(file,current,Collections.singletonMap(file, hf),filelistmap,true); 
			} else {
				addedFile = present;
			}
		}
		boolean addedOutsideOfShare = false;
		if (addedFile == null && sd == null && force) {
			addedFile = new SpecialFileListFile(hf, hiddenTop,restrictForUser);
			addedOutsideOfShare = true;
		}
		
		if (addedFile != null) {
			callback.addedFile(addedFile, addedOutsideOfShare);
		}
		//logger.debug("Added file: "+file+"  added? "+added);
		
	}
	
	
	
	
	

	public static class SearchParameter {
		public Set<String> keys;
		public Set<String> excludes;
		public long minsize,maxsize, equalsize;
		public Collection<String> fileendings;
		public int maxResults;
		public boolean onlyFolder;
		
		/**
		 * restricts the search to directories available in the given hub
		 */
		public FavHub hub;
		
		
		public SearchParameter(Set<String> keys, Set<String> excludes,
				long minsize, long maxsize, long equalsize,
				Collection<String> fileendings,
				boolean onlyFolder) {
			super();
			this.keys = keys;
			this.excludes = excludes;
			this.minsize = minsize;
			this.maxsize = maxsize;
			this.equalsize = equalsize;
			this.fileendings = fileendings;
			this.onlyFolder = onlyFolder;
		}
		
		
	}
	
	
	/* (non-Javadoc)
	 * @see uc.files.filelist.IOwnFileList#search(java.util.Set, java.util.Set, long, long, long, java.util.Collection, int, boolean)
	 */
	public Set<IFileListItem> search(SearchParameter sp) {
		lock.lock();
		try {
			if (!initialized) {
				return Collections.<IFileListItem>emptySet();
			}
		} finally {
			lock.unlock();
		}
	/*	if (logger.isDebugEnabled()) {
			String s = "";
			for (String part:keys) {
				s += part+",";
			}
			//logger.debug("Search("+s+","+sizerestricted+","+maxsize+","+size+","+""+","+maxResults);
		} */
		

		//search for everything
		if (filelistmap == null) {
			return Collections.<IFileListItem>emptySet();
		}
		FileFilter fileFilter = new FileFilter(sp.minsize,sp.maxsize,
				sp.equalsize,sp.fileendings,sp.onlyFolder);
		
		Set<IFileListItem> res = filelistmap.search(sp.keys,sp.excludes,fileFilter);

		logger.debug("Found nr: "+res);
			

		
		if (sp.keys.size() == 1 ) {
			String searched = GH.getRandomElement(sp.keys); 
			if (HashValue.isHash(searched) ) {
				FileListFile found = search(HashValue.createHash(searched));
				if (found != null) {
					res.add(found);
				}
			}
		}
		
		if (pdfIndex.isCreated() && res.isEmpty() && TextIndexer.matchesSomeEnding(sp.fileendings)) {

			Set<HashValue> found = pdfIndex.search(sp.keys, sp.excludes,sp.fileendings);
			res = new HashSet<IFileListItem>(); //res might not support adding... empty..
			for (HashValue h : found) {
				FileListFile found2 = search(h);
				
				if (found2 != null && fileFilter.filter(found2)) { 
					res.add(found2);
				}
			}
		}
		
		//cut the set down to max size if needed
		if (res.size() > sp.maxResults) {
			Iterator<IFileListItem> it = res.iterator();
			while (it.hasNext() && res.size() > sp.maxResults) {
				it.next();
				it.remove();
			}
		}

		return res;
	}
	
	/* (non-Javadoc)
	 * @see uc.files.filelist.IOwnFileList#search(uc.crypto.HashValue)
	 */
	public FileListFile search(HashValue tth) {
		lock.lock();
		try {
			if (!initialized) {
				return null;
			}
		} finally {
			lock.unlock();
		}
		FileListFile flf = fileList.search(tth);
		return flf;
	}
	
	
	
	
	@Override
	public FileListFile get(HashValue hash) {
		waitInitialize();
		FileListFile flf = fileList.search(hash);
		return flf;
	}




	/* (non-Javadoc)
	 * @see uc.files.filelist.IOwnFileList#getFile(uc.crypto.HashValue)
	 */
	public File getFile(HashValue tth)  {
		waitInitialize();

		FileListFile f = get(tth);
		if (f != null) {
			return getFile(f);
		} else {
			return null;
		}
	}
	
	private void waitInitialize() {
		lock.lock();
		try {
			while (!initialized) {
				fileListInitialized.awaitUninterruptibly();
			}
		} finally {
			lock.unlock();
		}
	}
	
	
	
	@Override
	public boolean isInitialized() {
		return initialized;
	}




	/* (non-Javadoc)
	 * @see uc.files.filelist.IOwnFileList#getFile(uc.files.filelist.FileListFile)
	 */
	public File getFile(FileListFile file)  {
		waitInitialize();
		if (file == null) {
			throw new IllegalArgumentException("file may not be null");
		}
		//determine level one folder
		FileListFolder cur = file.getParent(); 
		
		while (cur != null && !(cur instanceof TopFolder)) {
			cur = cur.getParent();
		}
		
		if (cur == null) {
			throw new IllegalStateException("Invalid FileListFile");
		}
		
		return ((TopFolder)cur).getRealPath(file);

	}
	
	/* (non-Javadoc)
	 * @see uc.files.filelist.IOwnFileList#getSharesize()
	 */
	public long getSharesize() {
		lock.lock();
		try {
			if (!initialized) {
				return PI.getLong(PI.lastFilelistSize);
			}
		} finally {
			lock.unlock();
		}
		return fileList.getSharesize();
	}
	

	/* (non-Javadoc)
	 * @see uc.files.filelist.IOwnFileList#getNumberOfFiles()
	 */
	public int getNumberOfFiles() {
		lock.lock();
		try {
			if (!initialized) {
				return PI.getInt(PI.lastNumberOfFiles);
			}
		} finally {
			lock.unlock();
		}
		return fileList.getNumberOfFiles();
	}


//	/**
//	 * indicates that the FileList can currently not be used..
//	 * used for a faster startup..
//	 * 
//	 * @author Quicksilver
//	 */
//	public static class FilelistNotReadyException extends Exception {
//		private static final long serialVersionUID = 1L;
//	}
	
	
	class RefreshJob extends Job {

		public RefreshJob() {
			super(LanguageKeys.RefreshingFilelist); 
			setPriority(Job.LONG);
		}

		@Override
		protected IStatus run(IProgressMonitor monitor) {
			//before and after refresh GC is run as lots of long lived objects get free 
			//that won't be garbage collected for quite a long time otherwise
			//(before to make space for refresh without enlarging heap
			//and afterwards to get rid of long lived objects)
			System.gc(); 
			
			refresh(monitor);
		
			System.gc(); 
			return Status.OK_STATUS;
		}
	}

	
	public FileList getFileList() {
		waitInitialize();
		return fileList;
	}


	

	
	public static class TopFolder extends FileListFolder {

		protected final List<SharedDir> sharedDirs = new CopyOnWriteArrayList<SharedDir>();

		public TopFolder(FileList f,SharedDir sd) {
			super(f.getRoot(), sd.getName());
			this.sharedDirs.add(sd);
		}
		
		public List<SharedDir> getSharedDirs() {
			return Collections.unmodifiableList(sharedDirs);
		}
		

//		public File getRealPath() {
//			return sharedDir.getDirectory();
//		}
		
		public File getRealPath(FileListFile decendant) {
			String realpath = decendant.getPath();
			String pathRelativeToLevelone = realpath.substring(realpath.indexOf(File.separatorChar)+1);
			for (SharedDir sd:sharedDirs) {
				File f = new File(sd.getDirectory(), pathRelativeToLevelone );
				if (f.isFile()) {
					return f;
				}
			}
			
			return null;
		}

	
	}
	
	public class TmpSharedTopFolder extends TopFolder {

		private boolean notifyChanges= true;
		public TmpSharedTopFolder(FileList f) {
			super(f,  new SharedDir("<temporarily shared files>",null));
		}
		
		public TmpSharedTopFolder copyTo(FileList newFilelist) {
			TmpSharedTopFolder newFolder = new TmpSharedTopFolder(newFilelist);
			newFolder.notifyChanges = false;
			for (FileListFile file:getFiles()) {
				SpecialFileListFile sflf = (SpecialFileListFile)file;
				new SpecialFileListFile(sflf, newFolder);
			}
			newFolder.notifyChanges = true;
			return newFolder;
		}
		

		@Override
		void addChild(FileListFile a) {
			if (! (a instanceof SpecialFileListFile)) {
				throw new IllegalStateException();
			}
			super.addChild(a);
			if (notifyChanges) {
				dcc.notifyChangedInfo(InfoChange.SHARESIZE_MANUAL);
			}
		}
		
		@Override
		public boolean isOriginal() {
			return false;
		}

//		@Override
//		public SharedDir getSharedDir() {
//			throw new IllegalStateException();
//		}
//
//		@Override
//		public boolean isOnline() {
//			return true;
//		}
//
//		@Override
//		public File getRealPath() {
//			throw new IllegalStateException();
//		}
		
		
		/**
		 * folder does not go to XML
		 * @throws SAXException 
		 */
		@Override
		public void writeToXML(TransformerHandler hd, AttributesImpl atts,
				boolean recursive, boolean isBase, boolean writeout) throws SAXException {
			super.writeToXML(hd, atts, recursive, isBase, writeout);
		}

		@Override
		public File getRealPath(FileListFile decendant) {
			if (decendant instanceof SpecialFileListFile) {
				return ((SpecialFileListFile)decendant).hf.getPath();
			}
			throw new IllegalStateException();
		}
	}
	
	public static class SpecialFileListFile extends FileListFile {
		private static final Object synchRestriction = new Object();
		private final HashedFile hf;
		private Set<IUser> restriction ;
		
		public SpecialFileListFile(SpecialFileListFile cc,TmpSharedTopFolder htf) {
			this(cc.hf,htf,null);
			synchronized(synchRestriction) {
				this.restriction = cc.restriction;
			}
		}
		public SpecialFileListFile(HashedFile hf,TmpSharedTopFolder htf,IUser restriction) {
			super(htf,hf.getPath().getName(),hf.getPath().length(),hf.getTTHRoot());
			this.hf = hf;
			synchronized(synchRestriction) {
				if (restriction != null) {
					this.restriction = new HashSet<IUser>();
					this.restriction.add(restriction);
				} else {
					restriction = null;
				}
			}
		}
		
	
	
		@Override
		public boolean automaticExtraSlot() {
			return true;
		}
		public void remove() {
			getParent().removeChild(this);
		}
		
		public void add(IUser restricted) {
			synchronized(synchRestriction) {
			if (restriction != null) {
				if (restricted == null) {
					restriction = null;
				} else {
					restriction.add(restricted);
				}
			}
			}
		}
		
		@Override
		public boolean mayDownload(IUser usr) {
			synchronized(synchRestriction) {
				return restriction == null || restriction.contains(usr);
			}
		}
		
		@Override
		public boolean isOriginal() {
			return false;
		}
		
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = super.hashCode();
			result = prime * result + ((hf == null) ? 0 : hf.hashCode());
			return result;
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!super.equals(obj))
				return false;
			if (getClass() != obj.getClass())
				return false;
			SpecialFileListFile other = (SpecialFileListFile) obj;
			if (hf == null) {
				if (other.hf != null)
					return false;
			} else if (!hf.equals(other.hf))
				return false;
			return true;
		}
		
		
	}
	
}



