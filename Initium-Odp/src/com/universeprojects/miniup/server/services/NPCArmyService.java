package com.universeprojects.miniup.server.services;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.appengine.api.datastore.Key;
import com.universeprojects.cacheddatastore.CachedEntity;
import com.universeprojects.miniup.server.GameUtils;
import com.universeprojects.miniup.server.ODPDBAccess;

public class NPCArmyService extends Service
{
	final static Logger log = Logger.getLogger(NPCArmyService.class.getName());
	final Key npcArmyKey;
	CachedEntity npcArmy;
	
	
	List<CachedEntity> npcs = null;
	
	
	
	public NPCArmyService(ODPDBAccess db, CachedEntity npcArmy)
	{
		super(db);
		this.npcArmyKey = npcArmy.getKey();
		this.npcArmy = npcArmy;
		
		log.setLevel(Level.FINEST);
	}
	
	public String getUniqueId()
	{
		return (String)npcArmy.getProperty("uniqueId");
	}

	public void setUniqueId(String uniqueId)
	{
		npcArmy.setProperty("uniqueId", uniqueId);
	}

	public long getPropagationCount()
	{
		return (Long)npcArmy.getProperty("propagationCount");
	}

	public void setPropagationCount(long propagationCount)
	{
		npcArmy.setProperty("propagationCount", propagationCount);
	}

	public double getSpawnsPerTick()
	{
		return (Double)npcArmy.getProperty("spawnsPerTick");
	}

	public void setSpawnsPerTick(double spawnsPerTick)
	{
		npcArmy.setProperty("spawnsPerTick", spawnsPerTick);
	}

	public boolean isSeed()
	{
		return Boolean.TRUE.equals(npcArmy.getProperty("seed"));
	}

	public void setSeed(Boolean seed)
	{
		npcArmy.setProperty("seed", seed);
	}

	public String getPropagatedMaxSpawnCount()
	{
		return (String)npcArmy.getProperty("propagatedMaxSpawnCount");
	}

	public void setPropagatedMaxSpawnCount(String propagatedMaxSpawnCount)
	{
		npcArmy.setProperty("propagatedMaxSpawnCount", propagatedMaxSpawnCount);
	}

	public Key getLocationKey()
	{
		return (Key)npcArmy.getProperty("locationKey");
	}

	public void setLocationKey(Key locationKey)
	{
		npcArmy.setProperty("locationKey", locationKey);
	}

	public Key getNpcDefKey()
	{
		return (Key)npcArmy.getProperty("npcDefKey");
	}

	public void setNpcDefKey(Key npcDefKey)
	{
		npcArmy.setProperty("npcDefKey", npcDefKey);
	}

	public long getMaxSpawnCount()
	{
		return (Long)npcArmy.getProperty("maxSpawnCount");
	}

	public void setMaxSpawnCount(long maxSpawnCount)
	{
		npcArmy.setProperty("maxSpawnCount", maxSpawnCount);
	}

	public NPCArmyService(ODPDBAccess db, Key npcArmyKey)
	{
		this(db, db.getEntity(npcArmyKey));
	}

	public List<CachedEntity> getNPCs()
	{
		if (npcs!=null)
			return npcs;
		
		npcs = db.getFilteredList("Character", 
				"locationKey", getLocationKey(), 
				"_definitionKey", getNpcDefKey());
		
		for(int i = npcs.size()-1; i>=0; i--)
			if ((Double)npcs.get(i).getProperty("hitpoints")<1d)
				npcs.remove(i);
		
		return npcs;
	}
	
	/**
	 * This should be called on the server tick, which is called once every 10 minutes.
	 */
	public void doTick()
	{
		// Check if we need to propagate
		attemptToPropagate();

		// Check if there are NO npcs left and seed is NOT set. If so, we'll delete the army as it has been defeated
		if (isSeed()==false && getNPCs().size()==0)
		{
			deleteNPCArmy();
			return;
		}
		
		// Spawn more monsters
		attemptToSpawn();
		
		if (npcArmy.isUnsaved())
			db.getDB().put(npcArmy);
	}
	
	private void deleteNPCArmy()
	{
		db.getDB().delete(npcArmyKey);
	}
	
	/**
	 * This method will attempt to spawn a new NPC at the army's location depending on the "spawns per tick" setting and only up 
	 * to a maximum of "max spawn count" for the location. 	
	 */
	private void attemptToSpawn()
	{
		if (getMaxSpawnCount()<=0L) return;
		
		if (getSpawnsPerTick()<=0d) return;
		
		if (getNPCs().size()>=getMaxSpawnCount()) return;
		
		
		
		if (isSeed())
		{
			CachedEntity npcDef = db.getEntity(getNpcDefKey());
			long spawnCount = Math.round(getSpawnsPerTick());
			if (spawnCount<1) spawnCount = 1;
			for(int i = 0; i<spawnCount; i++)
				db.doCreateMonster(npcDef, getLocationKey());

			// Once we've seeded the spawn, we'll want to turn off the seed field
			if (isSeed())
				setSeed(false);
		}
		else if (getSpawnsPerTick()<1 && GameUtils.roll(getSpawnsPerTick())==true)
		{
			CachedEntity npcDef = db.getEntity(getNpcDefKey());
			db.doCreateMonster(npcDef, getLocationKey());
		}
		else if (getSpawnsPerTick()>=1)
		{
			CachedEntity npcDef = db.getEntity(getNpcDefKey());
			long spawnCount = Math.round(getSpawnsPerTick());
			for(int i = 0; i<spawnCount; i++)
				db.doCreateMonster(npcDef, getLocationKey());
		}
	}
	
	
	/**
	 * This method will attempt to propagate the army to an adjacent location if it is time to do so.
	 * 
	 * Propagation only occurs if we have more "propagationCount" left AND if there are "maxSpawnCount"
	 * NPCs that are ALIVE in the area still.
	 */
	private void attemptToPropagate()
	{
		log.info("Attempting to propagate from "+getLocationKey()+"..");
		
		if (getPropagationCount()<=0) return;
		
		// If we are at or over the maxSpawnCount, then we will attempt to propagate
		if (getNPCs().size()>=getMaxSpawnCount())
		{
			// Get all the "permanent" paths that lead away from here
			List<CachedEntity> paths = db.getPathsByLocation_PermanentOnly(getLocationKey());
			
			Collections.shuffle(paths);
			
			// Now get all the location keys that are on the other side of the paths that lead away from here...
			Map<CachedEntity, Key> pathsToLocations = new HashMap<CachedEntity, Key>();
			for(CachedEntity path:paths)
			{
				if (GameUtils.equals(path.getProperty("location1Key"), getLocationKey()))
					pathsToLocations.put(path, (Key)path.getProperty("location2Key"));
				else
					pathsToLocations.put(path, (Key)path.getProperty("location1Key"));
			}

			log.info("Number of paths: "+paths.size());
			// Choose a path. Paths are already shuffled so we'll just choose at random
			for(int i = 0; i<paths.size(); i++)
			{
				if (getPropagationCount()<=0) return;
				
				if (paths.get(i).getProperty("discoveryChance")==null || (Double)paths.get(i).getProperty("discoveryChance")<=0d)
					continue;
				
				CachedEntity pathToPropagateTo = paths.get(i);
				Key locationToPropagateTo = pathsToLocations.get(pathToPropagateTo);
				
				// If there is already an NPCArmy at this location, we will add to it
				List<CachedEntity> npcArmiesAtNextLocation = db.getFilteredList("NPCArmy", "locationKey", locationToPropagateTo);
				
				CachedEntity npcArmyAtNextLocation = null;
				for(CachedEntity npcArmyAtNextLocationCandidate:npcArmiesAtNextLocation)
					if (GameUtils.equals(npcArmyAtNextLocationCandidate.getProperty("npcDefKey"), getNpcDefKey()))
					{
						npcArmyAtNextLocation = npcArmyAtNextLocationCandidate;
						break;
					}
				
				
				
				// If there is no army at the next location, we will create one. If there is, we'll add a propagation value to it
				if (npcArmyAtNextLocation==null)
				{
					CachedEntity locationEntity = db.getEntity(locationToPropagateTo);
					if (locationEntity==null) continue; // Cancel propagation to here, the location is deleted
					log.info("Propagating to a new location: "+locationToPropagateTo);
					CachedEntity newNpcArmy = new CachedEntity("NPCArmy");
					newNpcArmy.setProperty("maxSpawnCount", db.solveCurve_Long(getPropagatedMaxSpawnCount()));
					newNpcArmy.setProperty("propagatedMaxSpawnCount", getPropagatedMaxSpawnCount());
					newNpcArmy.setProperty("propagationCount", 0L);
					newNpcArmy.setProperty("seed", true);
					newNpcArmy.setProperty("spawnsPerTick", getSpawnsPerTick());
					newNpcArmy.setProperty("npcDefKey", getNpcDefKey());
					newNpcArmy.setProperty("locationKey", locationToPropagateTo);
					newNpcArmy.setProperty("uniqueId", getUniqueId());
					newNpcArmy.setProperty("name", getUniqueId()+": "+locationEntity.getProperty("name")+"(max: "+newNpcArmy.getProperty("maxSpawnCount")+")");
	
					setPropagationCount(getPropagationCount()-1);

					db.getDB().put(newNpcArmy);
				}
				else
				{
					Long nextLocationPropagationCount = (Long)npcArmyAtNextLocation.getProperty("propagationCount");
					// If the location we're propagating to has the same or more propagationCount, then don't bother performing the propagation
					// and try the next path instead
					if (nextLocationPropagationCount>=getPropagationCount())
					{
						log.finest("Was going to propagate to "+locationToPropagateTo+" but it has "+nextLocationPropagationCount+" propagation count when we only have "+getPropagationCount()+", so we're going to skip propagation here and try another path.");
						continue;
					}
					
					log.info("Propagating to a location we previous propagated to before: "+locationToPropagateTo);
					npcArmyAtNextLocation.setProperty("propagationCount", nextLocationPropagationCount+1);
					setPropagationCount(getPropagationCount()-1);
					
					db.getDB().put(npcArmyAtNextLocation);
					
				}
			}			
			
		}
	}
	
	
}
