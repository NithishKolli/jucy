package uc.database;

import helpers.GH;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;

import uc.DCClient;
import uc.FavHub;
import uc.IUser;
import uc.crypto.HashValue;


/**
 * wrapper around the database for easier logging access..
 * 
 * @author Quicksilver
 *
 */
public class DBLogger {


	private final String name;

	private final HashValue entityID;
	
	private final IDatabase database;
	
	//private long[] stats; //3 entrys first, last , total;
	
	/**
	 * 
	 * @param usr
	 * @param dcc
	 */
	public DBLogger(IUser usr,DCClient dcc) {
		this(usr.getNick(),usr.getUserid(),dcc.getDatabase());
	}
	

	
	/**
	 * @param favHub - the hub for which to create..
	 * @param mc - true for a mc logger.. false for a feedlogger..
	 */
	public DBLogger(FavHub favHub,boolean mc,DCClient dcc) {
		this((mc?"MC: ": "Feed: ")+favHub.getSimpleHubaddy(),favHub.getEntityID(mc),dcc.getDatabase());
	}
	

	
	public DBLogger(String name, HashValue entityId,IDatabase database) {
		this.name = name;
		this.entityID = entityId;
		this.database = database;
	}

	
	
	/**
	 * 
	 * @param message - the message to be logged..
	 * @return a logEntryrepresenting that.. usr and messagew for this logging Manager..
	 * 
	 */
	private ILogEntry create(final String message,final long date) {
		return new ILogEntry() {
			public long getDate() {
				return date;
			}
			
			public HashValue getEntityID() {
				return entityID;
			}

			public String getMessage() {
				return message;
			}

			public String getName() {
				return name;
			}

			@Override
			public int compareTo(ILogEntry o) {
				return GH.compareTo(getDate(), o.getDate());
			}
			
		};
	}
	
	public void addLogEntry(String message,long date) {
		final ILogEntry log = create(  message,date); 
		if (database != null) {
			database.addLogEntry(log);
		}
	}
	
	/**
	 * 
	 * @deprecated
	 */
	public List<ILogEntry> loadLogEntrys(int maxAmmount,int offset) {
		return database.getLogentrys(entityID, maxAmmount, offset);
	}
	
	public List<ILogEntry> loadLogEntrys(long fromTimeStamp,long toTimestamp) {
		return database.getLogentrys(entityID, fromTimeStamp,toTimestamp );
	}
	
	public List<Long> getDays() {
		return database.getDaysWithLogs(entityID);
	}
	
	public int countLogEntrys() {
		return database.countLogentrys(entityID);
	}
	

	public void writeToFile(File f,IProgressMonitor monitor) throws IOException {
		database.writeLogToFile(entityID, f, monitor);
	}
	
	
	public void deleteEntity(IProgressMonitor monitor) {
		database.pruneLogentrys(entityID, new Date(Long.MAX_VALUE),monitor);
	}

	public String getName() {
		return name;
	}

	
	public long getFirstLogNextTo(long timestamp,boolean forward) {
		return database.getFirstLogNextTo(entityID,  new Date(timestamp), forward);

	}


	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result
				+ ((entityID == null) ? 0 : entityID.hashCode());
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
		DBLogger other = (DBLogger) obj;
		if (entityID == null) {
			if (other.entityID != null)
				return false;
		} else if (!entityID.equals(other.entityID))
			return false;
		return true;
	}
	
	

}
