package uc;

import helpers.Observable;
import helpers.PrefConverter;



import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


import logger.LoggerFactory;


import org.apache.log4j.Logger;
import org.eclipse.core.runtime.Platform;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import uc.FavHub.FavHubTranslater;


/**
 * holds all favorite hubs
 * @author Quicksilver
 *
 */
public class FavHubs extends Observable<FavHub> implements IFavHubs,Serializable {

	private static final long serialVersionUID = 1L;

	private static final Logger logger = LoggerFactory.make();
	

	
	
	@SuppressWarnings("serial")
	private final Map<Integer,FavHub> favHubs = Collections.synchronizedMap(new HashMap<Integer,FavHub>() {

		/**
		 * 
		 * make sure new Order is set to the hub..
		 */
		@Override
		public FavHub put(Integer key, FavHub value) {
			if (!key.equals(value.getOrder())) {
				value.setOrder(key);
			}
			return super.put(key, value);
		}
	});
	
	private final transient DCClient dcc;
	
	public FavHubs(DCClient dcc) {
		this.dcc = dcc;
		load();
	}
	
	/* (non-Javadoc)
	 * @see uc.IFavHubs#store()
	 */
	@Override
	public void store()  {
	//	boolean legacymode = false;
	//	if (!legacymode) {
		logger.debug("Storeing FavHubs modern way");
		synchronized(favHubs) {
			String p = PrefConverter.createList(
					new ArrayList<FavHub>(favHubs.values()),
					new FavHubTranslater());
			
			
			if (!PI.put(PI.favHubs2, p)) {
				logger.error("Could not store FavHub");
			}
		}

		notifyObservers();
	}
	
	private void load() {
		String fav = PI.get(PI.favHubs2);
		if (fav.equals(PI.LegacyMode)) {
			logger.debug("Loading FavHubs legacy way");
			Preferences pref = PI.get().node(PI.favHubs);
			Map<Integer,FavHub> ret = new HashMap<Integer,FavHub>();
			try {
				for (String child : pref.childrenNames()) {
					Preferences p = pref.node(child);
					FavHub fh = FavHub.loadHub(p);
					ret.put(fh.getOrder(),fh);
				}
			//	loadedInLegacyMode = true;
			} catch(BackingStoreException bse) {
				bse.printStackTrace();
			}
			favHubs.putAll(ret);
		} else {
			logger.debug("Loading FavHubs modern way");
			List<FavHub> fhList = PrefConverter.parseString(fav, new FavHubTranslater());
			
			//String[] all = PrefConverter.asArray(fav);
			for (FavHub fh:fhList) {
				favHubs.put(fh.getOrder(), fh);
				//favHubs.put(i, FavHub.loadHub(all[i], i));
			}
		}
		notifyObservers(null);
	}
	
	/* (non-Javadoc)
	 * @see uc.IFavHubs#getFavHubs()
	 */
	@Override
	public List<FavHub> getFavHubs() {
		return Collections.unmodifiableList(new ArrayList<FavHub>(favHubs.values()));
	}
	
	/**
	 * adds the hub to favorites .. (setting the order if not set)
	 */
	@Override
	public void addToFavorites(FavHub hub) {
		if (hub.getOrder() == -1) {
			hub.setOrder(favHubs.size());
		}
		favHubs.put(hub.getOrder(), hub);
		store();
		notifyObservers();
		checkOrder();
	}
	
	/**
	 * removes a hub from FavHubs and moves all other hubs 
	 * @param hub
	 */
	@Override
	public void removeFromFavorites(FavHub hub) {
		favHubs.remove(hub.getOrder());
		repairOrders();
		/*for (int i = hub.getOrder()+1 ; i < favHubs.size() ; i++ ) {
			favHubs.put(i-1,  favHubs.get(i));
		} */
		notifyObservers();
		checkOrder();
	}
	
	/**
	 * changes the order of the hub
	 * @param favHub - the hub 
	 * @param up
	 */
	@Override
	public void changeOrder(FavHub favHub, boolean up) {
		int curOrder = favHub.getOrder();
		int newOrder = curOrder + (up? -1:1);
		if (newOrder >= 0 && newOrder < favHubs.size()) {
			FavHub other = favHubs.get(newOrder);
			favHubs.put(newOrder, favHub);
			favHubs.put(curOrder, other);
			notifyObservers();
		}
		checkOrder();
	}
	
	/**
	 * exchanges 2 hubs with same order..
	 * 
	 * @param old
	 * @param newHub
	 */
	@Override
	public void exchange(FavHub old,FavHub newHub) {
		if (old.getOrder() != newHub.getOrder()) {
			throw new IllegalArgumentException();
		}  else if (newHub.getOrder() == -1) {
			addToFavorites(newHub);
		} else {
			favHubs.put(newHub.getOrder(), newHub);
		}
	}


	/* (non-Javadoc)
	 * @see uc.IFavHubs#openAutoStartHubs()
	 */
	@Override
	public void openAutoStartHubs() {
		dcc.executeDir(new Runnable() {
			public void run() {
				for (int i=0; i < favHubs.size(); i++) {
					FavHub favHub = favHubs.get(i);
					if (favHub == null) { //try repairing on startup if something goes wrong..
						repairOrders();
						store();
					} else if (favHub.isAutoconnect()) {
						favHub.connect(dcc);
					}
				}
				checkOrder();
			}
		});
	}
	
	
	/* (non-Javadoc)
	 * @see uc.IFavHubs#contains(java.lang.String)
	 */
	public boolean contains(String hubaddress) {
		FavHub fhNew = new FavHub(hubaddress);
		for (FavHub fh: favHubs.values()) {
			if (fh.equals(fhNew)) {
				return true;
			}
		}
		checkOrder();
		return false;
	}
	
	
	/* (non-Javadoc)
	 * @see uc.IFavHubs#contains(uc.FavHub)
	 */
	public boolean contains(FavHub hub) {
		return hub.equals(favHubs.get(hub.getOrder()));	
	}
	
	public void favHubChanged(FavHub hub) {
		
	}
	
	/**
	 * retrieves internal presentation of given  FavHub
	 * i.e. if a hub with the given husb address exists that hub is returned..
	 * 
	 * @param fh
	 * @return
	 */
	public FavHub internal(FavHub fh) {
		for (FavHub present:getFavHubs()) {
			if (fh.equals(present)) {
				return present;
			}
		}
		return fh;
	}
	
	
	/**
	 * if one Order is missing in between
	 * this shifts all elements a bit
	 */
	private void repairOrders() {
		int j= 0;
		for (int i = 0; i < favHubs.size() ; ) {
			FavHub hub = favHubs.get(i+j);
			if (hub == null) {
				j++;
			} else {
				if (j != 0) {
					favHubs.remove(i+j);
					favHubs.put(i, hub);
				}
				i++;
			}
		}
		checkOrder();
	}
	
	private void checkOrder() {
		for (int i = 0; i < favHubs.size() ; i++ ) {
			FavHub hub = favHubs.get(i);
			if (hub == null && Platform.inDevelopmentMode()) {
				logger.warn("hubs not in order",new Throwable());
			} 
		}
	}
	
}
