/*
    PopulationDensity Server Plugin for Minecraft
    Copyright (C) 2011 Ryan Hamshire

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ryanhamshire.PopulationDensity;

import java.io.*;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang.Validate;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;

public class DataStore implements TabCompleter
{
	//in-memory cache for player home region, because it's needed very frequently
	private HashMap<String, PlayerData> playerNameToPlayerDataMap = new HashMap<String, PlayerData>();
	
	//path information, for where stuff stored on disk is well...  stored
	private final static String dataLayerFolderPath = "plugins" + File.separator + "PopulationDensityData";
	private final static String playerDataFolderPath = dataLayerFolderPath + File.separator + "PlayerData";
	private final static String regionDataFolderPath = dataLayerFolderPath + File.separator + "RegionData";
	public final static String configFilePath = dataLayerFolderPath + File.separator + "config.yml";
	final static String messagesFilePath = dataLayerFolderPath + File.separator + "messages.yml";
	
    //in-memory cache for messages
    private String [] messages;
	
	//currently open region
	private RegionCoordinates openRegionCoordinates;
	
	//coordinates of the next region which will be opened, if one needs to be opened
	private RegionCoordinates nextRegionCoordinates;
	
	//region data cache
	private ConcurrentHashMap<String, RegionCoordinates> nameToCoordsMap = new ConcurrentHashMap<String, RegionCoordinates>();
	private ConcurrentHashMap<RegionCoordinates, String> coordsToNameMap = new ConcurrentHashMap<RegionCoordinates, String>();
	
	//initialization!
	public DataStore(List<String> regionNames)
	{
		//ensure data folders exist
		new File(playerDataFolderPath).mkdirs();
		new File(regionDataFolderPath).mkdirs();
		
		this.regionNamesList = regionNames.toArray(new String[]{});
		
		this.loadMessages();
		
		//get a list of all the files in the region data folder
        //some of them are named after region names, others region coordinates
        File regionDataFolder = new File(regionDataFolderPath);
        File [] files = regionDataFolder.listFiles();           
        
        for(int i = 0; i < files.length; i++)
        {               
            if(files[i].isFile())  //avoid any folders
            {
                try
                {
                    //if the filename converts to region coordinates, add that region to the list of defined regions
                    //(this constructor throws an exception if it can't do the conversion)
                    RegionCoordinates regionCoordinates = new RegionCoordinates(files[i].getName());
                    String regionName = Files.readFirstLine(files[i], Charset.forName("UTF-8"));
                    this.nameToCoordsMap.put(regionName.toLowerCase(), regionCoordinates);
                    this.coordsToNameMap.put(regionCoordinates, regionName);
                }
                
                //catch for files named after region names
                catch(Exception e){ }                   
            }
        }
		
		//study region data and initialize both this.openRegionCoordinates and this.nextRegionCoordinates
		this.findNextRegion();
		
		//if no regions were loaded, create the first one
		if(nameToCoordsMap.keySet().size() == 0)
		{
			PopulationDensity.AddLogEntry("Proszę o cierpliwość! Właśnie będę szukał nowych regionów dla graczy!");
			PopulationDensity.AddLogEntry("Proces skanowania może chwilę potrwać, zwłaszcza jeśli na tym świecie gracze już coś budowali.");
			this.addRegion();			
		}
		
		PopulationDensity.AddLogEntry("Otwarty region: \"" + this.getRegionName(this.getOpenRegion()) + "\" na " + this.getOpenRegion().toString() + ".");
	}
	
	//used in the spiraling code below (see findNextRegion())
	private enum Direction { left, right, up, down }	
	
	//starts at region 0,0 and spirals outward until it finds a region which hasn't been initialized
	//sets private variables for openRegion and nextRegion when it's done
	//this may look like black magic, but seriously, it produces a tight spiral on a grid
	//coding this made me reminisce about seemingly pointless computer science exercises in college
	public int findNextRegion()
	{
		//spiral out from region coordinates 0, 0 until we find coordinates for an uninitialized region
		int x = 0; int z = 0;
		
		//keep count of the regions encountered
		int regionCount = 0;

		//initialization
		Direction direction = Direction.down;   //direction to search
		int sideLength = 1;  					//maximum number of regions to move in this direction before changing directions
		int side = 0;        					//increments each time we change directions.  this tells us when to add length to each side
		this.openRegionCoordinates = new RegionCoordinates(0, 0);
		this.nextRegionCoordinates = new RegionCoordinates(0, 0);

		//while the next region coordinates are taken, walk the spiral
		while (this.getRegionName(this.nextRegionCoordinates) != null)
		{
			//loop for one side of the spiral
			for (int i = 0; i < sideLength && this.getRegionName(this.nextRegionCoordinates) != null; i++)
			{
				regionCount++;
				
				//converts a direction to a change in X or Z
				if (direction == Direction.down) z++;
				else if (direction == Direction.left) x--;
				else if (direction == Direction.up) z--;
				else x++;
				
				this.openRegionCoordinates = this.nextRegionCoordinates;
				this.nextRegionCoordinates = new RegionCoordinates(x, z);
			}
		
			//after finishing a side, change directions
			if (direction == Direction.down) direction = Direction.left;
			else if (direction == Direction.left) direction = Direction.up;
			else if (direction == Direction.up) direction = Direction.right;
			else direction = Direction.down;
			
			//keep count of the completed sides
			side++;

			//on even-numbered sides starting with side == 2, increase the length of each side
			if (side % 2 == 0) sideLength++;
		}
		
		//return total number of regions seen
		return regionCount;
	}
	
	//picks a region at random (sort of)
	public RegionCoordinates getRandomRegion(RegionCoordinates regionToAvoid)
	{
		if(this.coordsToNameMap.keySet().size() < 2) return null;
		
		//initialize random number generator with a seed based the current time
		Random randomGenerator = new Random();
		
		ArrayList<RegionCoordinates> possibleDestinations = new ArrayList<RegionCoordinates>();
		for(RegionCoordinates coords : this.coordsToNameMap.keySet())
		{
		    if(!coords.equals(regionToAvoid))
		    {
		        possibleDestinations.add(coords);
		    }
		}
		
		//pick one of those regions at random
		int randomRegion = randomGenerator.nextInt(possibleDestinations.size());			
		return possibleDestinations.get(randomRegion);			
	}
	
	public void savePlayerData(OfflinePlayer player, PlayerData data)
	{
		//save that data in memory
		this.playerNameToPlayerDataMap.put(player.getUniqueId().toString(), data);
		
		BufferedWriter outStream = null;
		try
		{
			//open the player's file
			File playerFile = new File(playerDataFolderPath + File.separator + player.getUniqueId().toString());
			playerFile.createNewFile();
			outStream = new BufferedWriter(new FileWriter(playerFile));
			
			//first line is home region coordinates
			outStream.write(data.homeRegion.toString());
			outStream.newLine();
			
			//second line is last disconnection date,
			//note use of the ROOT locale to avoid problems related to regional settings on the server being updated
			DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.FULL, Locale.ROOT);			
			outStream.write(dateFormat.format(data.lastDisconnect));
			outStream.newLine();
			
			//third line is login priority
			outStream.write(String.valueOf(data.loginPriority));
			outStream.newLine();
		}		
		
		//if any problem, log it
		catch(Exception e)
		{
			PopulationDensity.AddLogEntry("PopulationDensity: Nieoczekiwany wyjątek podczas zapisywania informacji o graczu \"" + player.getName() + "\": " + e.getMessage());
		}		
		
		try
		{
			//close the file
			if(outStream != null) outStream.close();
		}
		catch(IOException exception){}
	}
	
	public PlayerData getPlayerData(OfflinePlayer player)
	{
		//first, check the in-memory cache
		PlayerData data = this.playerNameToPlayerDataMap.get(player.getUniqueId().toString());

		if(data != null) return data;
		
		//if not there, try to load the player from file using UUID		
		loadPlayerDataFromFile(player.getUniqueId().toString(), player.getUniqueId().toString());

		//check again
		data = this.playerNameToPlayerDataMap.get(player.getUniqueId().toString());
		
		if(data != null) return data;

		//if still not there, try player name
		loadPlayerDataFromFile(player.getName(), player.getUniqueId().toString());
		
		//check again
        data = this.playerNameToPlayerDataMap.get(player.getUniqueId().toString());
        
        if(data != null) return data;

        return new PlayerData();
	}
	
	private void loadPlayerDataFromFile(String source, String dest)
	{
		//load player data into memory		
		File playerFile = new File(playerDataFolderPath + File.separator + source);
		
		BufferedReader inStream = null;
		try
		{					
			PlayerData playerData = new PlayerData();
			inStream = new BufferedReader(new FileReader(playerFile.getAbsolutePath()));
						
			//first line is home region coordinates
			String homeRegionCoordinatesString = inStream.readLine();
			
			//second line is date of last disconnection
			String lastDisconnectedString = inStream.readLine();
			
			//third line is login priority
			String rankString = inStream.readLine(); 
			
			//convert string representation of home coordinates to a proper object
			RegionCoordinates homeRegionCoordinates = new RegionCoordinates(homeRegionCoordinatesString);
			playerData.homeRegion = homeRegionCoordinates;
			  
			//parse the last disconnect date string
			try
			{
				DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.FULL, Locale.ROOT);
				Date lastDisconnect = dateFormat.parse(lastDisconnectedString);
				playerData.lastDisconnect = lastDisconnect;
			}
			catch(Exception e)
			{
				playerData.lastDisconnect = Calendar.getInstance().getTime();
			}
			
			//parse priority string
			if(rankString == null || rankString.isEmpty())
			{
				playerData.loginPriority = 0;
			}
			
			else
			{
				try
				{
					playerData.loginPriority = Integer.parseInt(rankString);
				}
				catch(Exception e)
				{
					playerData.loginPriority = 0;
				}			
			}
			  
			//shove into memory for quick access
			this.playerNameToPlayerDataMap.put(dest, playerData);
		}
		
		//if the file isn't found, just don't do anything (probably a new-to-server player)
		catch(FileNotFoundException e) 
		{ 
			return;
		}
		
		//if there's any problem with the file's content, log an error message and skip it		
		catch(Exception e)
		{
			 PopulationDensity.AddLogEntry("Niepomyślne ładowanie danych gracza: \"" + source + "\": " + e.getMessage());
		}
		
		try
		{
			if(inStream != null) inStream.close();
		}
		catch(IOException exception){}		
	}
	
	//adds a new region, assigning it a name and updating local variables accordingly
	public RegionCoordinates addRegion()
	{
		//first, find a unique name for the new region
		String newRegionName; 
		
		//select a name from the list of region names		
		//strategy: use names from the list in rotation, appending a number when a name is already used
		//(redstone, mountain, valley, redstone1, mountain1, valley1, ...)
		
		int newRegionNumber = this.coordsToNameMap.keySet().size() - 1;
		
		//as long as the generated name is already in use, move up one name on the list
		do
		{
			newRegionNumber++;
			int nameBodyIndex = newRegionNumber % this.regionNamesList.length;
			int nameSuffix = newRegionNumber / this.regionNamesList.length;
			newRegionName = this.regionNamesList[nameBodyIndex];
			if(nameSuffix > 0) newRegionName += nameSuffix;
			
		}while(this.getRegionCoordinates(newRegionName) != null);
		
	    this.privateNameRegion(this.nextRegionCoordinates, newRegionName);
		
		//find the next region in the spiral (updates this.openRegionCoordinates and this.nextRegionCoordinates)
		this.findNextRegion();
		
		return this.openRegionCoordinates;
	}
	
	//names a region, never throws an exception for name content
	private void privateNameRegion(RegionCoordinates coords, String name)
	{
	    //delete any existing data for the region at these coordinates
        String oldRegionName = this.getRegionName(coords);
        if(oldRegionName != null)
        {
            File oldRegionCoordinatesFile = new File(regionDataFolderPath + File.separator + coords.toString());
            oldRegionCoordinatesFile.delete();
            
            File oldRegionNameFile = new File(regionDataFolderPath + File.separator + oldRegionName);
            oldRegionNameFile.delete();
            this.coordsToNameMap.remove(coords);
            this.nameToCoordsMap.remove(oldRegionName.toLowerCase());
        }

        //"create" the region by saving necessary data to disk
        BufferedWriter outStream = null;
        try
        {
            //coordinates file contains the region's name
            File regionCoordinatesFile = new File(regionDataFolderPath + File.separator + coords.toString());
            regionCoordinatesFile.createNewFile();
            outStream = new BufferedWriter(new FileWriter(regionCoordinatesFile));
            outStream.write(name);
            outStream.close();
            
            //cache in memory
            this.coordsToNameMap.put(coords, name);
            this.nameToCoordsMap.put(name.toLowerCase(), coords);
        }
        
        //in case of any problem, log the details
        catch(Exception e)
        {
            PopulationDensity.AddLogEntry("Niespodziewany wyjątek: " + e.getMessage());
        }
        
        try
        {
            if(outStream != null) outStream.close();        
        }
        catch(IOException exception){}
    }

    //names or renames a specified region
	public void nameRegion(RegionCoordinates coords, String name) throws RegionNameException
	{
		//validate name
	    String error = PopulationDensity.instance.getRegionNameError(name, false);
	    if(error != null)
	    {
	        throw new RegionNameException(error);
	    }
	    
	    this.privateNameRegion(coords, name);
	}

	//retrieves the open region's coordinates
	public RegionCoordinates getOpenRegion()
	{
		return this.openRegionCoordinates;
	}
	
	//goes to disk to get the name of a region, given its coordinates
	public String getRegionName(RegionCoordinates coordinates)
	{
		return this.coordsToNameMap.get(coordinates);
	}
	
	//similar to above, goes to disk to get the coordinates that go with a region name
	public RegionCoordinates getRegionCoordinates(String regionName)
	{
		return this.nameToCoordsMap.get(regionName.toLowerCase());
	}
	
	//actually edits the world to create a region post at the center of the specified region	
	@SuppressWarnings("deprecation")
    public void AddRegionPost(RegionCoordinates region) throws ChunkLoadException
	{
		//if region post building is disabled, don't do anything
		if(!PopulationDensity.instance.buildRegionPosts) return;
		
		//find the center
		Location regionCenter = PopulationDensity.getRegionCenter(region, false);		
		int x = regionCenter.getBlockX();
		int z = regionCenter.getBlockZ();
		int y;

		//make sure data is loaded for that area, because we're about to request data about specific blocks there
		PopulationDensity.GuaranteeChunkLoaded(x, z);
		
		//sink lower until we find something solid
		//also ignore glowstone, in case there's already a post here!
		Material blockType;
		
		//find the highest block.  could be the surface, a tree, some grass...
		y = PopulationDensity.ManagedWorld.getHighestBlockYAt(x, z) + 1;
		
		//posts fall through trees, snow, and any existing post looking for the ground
		do
		{
			blockType = PopulationDensity.ManagedWorld.getBlockAt(x, --y, z).getType();
		}
		while(	y > 0 && (
				blockType == Material.AIR 		|| 
				blockType == Material.LEAVES 	|| 
		        blockType == Material.LEAVES_2  ||
				blockType == Material.LONG_GRASS||
				blockType == Material.LOG       ||
		        blockType == Material.LOG_2     ||
				blockType == Material.SNOW 		||
				blockType == Material.VINE					
				));
				
		if(blockType == Material.SIGN_POST)
		{
		    y -= 4;
		}
		else if(blockType == Material.GLOWSTONE || (blockType == Material.getMaterial(PopulationDensity.instance.postTopperId)))
		{
		    y -= 3;
		}
		else if(blockType == Material.BEDROCK)
		{
		    y += 1;
		}
		
		//if y value is under sea level, correct it to sea level (no posts should be that difficult to find)
		if(y < PopulationDensity.instance.minimumRegionPostY)
		{
			y = PopulationDensity.instance.minimumRegionPostY;
		}
		
		//clear signs from the area, this ensures signs don't drop as items 
		//when the blocks they're attached to are destroyed in the next step
		for(int x1 = x - 2; x1 <= x + 2; x1++)
		{
			for(int z1 = z - 2; z1 <= z + 2; z1++)
			{
				for(int y1 = y + 1; y1 <= y + 5; y1++)
				{
					Block block = PopulationDensity.ManagedWorld.getBlockAt(x1, y1, z1);
					if(block.getType() == Material.SIGN_POST || block.getType() == Material.SIGN || block.getType() == Material.WALL_SIGN)
						block.setType(Material.AIR);					
				}
			}
		}
		
		//clear above it - sometimes this shears trees in half (doh!)
		for(int x1 = x - 2; x1 <= x + 2; x1++)
		{
			for(int z1 = z - 2; z1 <= z + 2; z1++)
			{
				for(int y1 = y + 1; y1 < y + 10; y1++)
				{
					Block block = PopulationDensity.ManagedWorld.getBlockAt(x1, y1, z1);
					if(block.getType() != Material.AIR) block.setType(Material.AIR);
				}
			}
		}	

		//Sometimes we don't clear high enough thanks to new ultra tall trees in jungle biomes
		//Instead of attempting to clear up to nearly 110 * 4 blocks more, we'll just see what getHighestBlockYAt returns
		//If it doesn't return our post's y location, we're setting it and all blocks below to air.
		int highestBlockY = PopulationDensity.ManagedWorld.getHighestBlockYAt(x, z);
		while (highestBlockY > y)
		{
			Block block = PopulationDensity.ManagedWorld.getBlockAt(x, highestBlockY, z);
			if(block.getType() != Material.AIR)
				block.setType(Material.AIR);
			highestBlockY--;
		}

		//build top block
        PopulationDensity.ManagedWorld.getBlockAt(x, y + 3, z).setTypeIdAndData(PopulationDensity.instance.postTopperId, PopulationDensity.instance.postTopperData.byteValue(), true);
		
		//build outer platform
		for(int x1 = x - 2; x1 <= x + 2; x1++)
		{
			for(int z1 = z - 2; z1 <= z + 2; z1++)
			{
				PopulationDensity.ManagedWorld.getBlockAt(x1, y, z1).setTypeIdAndData(PopulationDensity.instance.outerPlatformId, PopulationDensity.instance.outerPlatformData.byteValue(), true);
			}
		}
		
		//build inner platform
        for(int x1 = x - 1; x1 <= x + 1; x1++)
        {
            for(int z1 = z - 1; z1 <= z + 1; z1++)
            {
                PopulationDensity.ManagedWorld.getBlockAt(x1, y, z1).setTypeIdAndData(PopulationDensity.instance.innerPlatformId, PopulationDensity.instance.innerPlatformData.byteValue(), true);
            }
        }
        
        //build lower center blocks
        for(int y1 = y; y1 <= y + 2; y1++)
        {
            PopulationDensity.ManagedWorld.getBlockAt(x, y1, z).setTypeIdAndData(PopulationDensity.instance.postId, PopulationDensity.instance.postData.byteValue(), true);
        }
		
		//build a sign on top with region name (or wilderness if no name)
		String regionName = this.getRegionName(region);
		if(regionName == null) regionName = "Dzicz";
		regionName = PopulationDensity.capitalize(regionName);
		Block block = PopulationDensity.ManagedWorld.getBlockAt(x, y + 4, z);
		block.setType(Material.SIGN_POST);
		
		org.bukkit.block.Sign sign = (org.bukkit.block.Sign)block.getState();
		sign.setLine(1, "Region");
		sign.setLine(2, PopulationDensity.capitalize(regionName));
		sign.update();
		
		//add a sign for the region to the south
		regionName = this.getRegionName(new RegionCoordinates(region.x + 1, region.z));
		if(regionName == null) regionName = "Dzicz";
		regionName = PopulationDensity.capitalize(regionName);
		
		block = PopulationDensity.ManagedWorld.getBlockAt(x, y + 2, z - 1);
		
		org.bukkit.material.Sign signData = new org.bukkit.material.Sign(Material.WALL_SIGN);
		signData.setFacingDirection(BlockFace.NORTH);
		
		block.setTypeIdAndData(Material.WALL_SIGN.getId(), signData.getData(), false);
		
		sign = (org.bukkit.block.Sign)block.getState();
		
		sign.setLine(0, "<--");
		sign.setLine(1, "Region:");
		sign.setLine(2, regionName);
		sign.setLine(3, "<--");
		
		sign.update();
		
		//if a city world is defined, also add a /cityregion sign on the east side of the post
		if(PopulationDensity.CityWorld != null)
		{
			block = PopulationDensity.ManagedWorld.getBlockAt(x, y + 3, z - 1);
			
			//signData = new org.bukkit.material.Sign(Material.WALL_SIGN);
			//signData.setFacingDirection(BlockFace.NORTH);
			
			//block.setTypeIdAndData(Material.WALL_SIGN.getId(), signData.getData(), false);
			
			//sign = (org.bukkit.block.Sign)block.getState();
			
			//sign.update();
		}
		
		//add a sign for the region to the east
		regionName = this.getRegionName(new RegionCoordinates(region.x, region.z - 1));
		if(regionName == null) regionName = "Dzicz";
		regionName = PopulationDensity.capitalize(regionName);
		
		block = PopulationDensity.ManagedWorld.getBlockAt(x - 1, y + 2, z);
		
		signData = new org.bukkit.material.Sign(Material.WALL_SIGN);
		signData.setFacingDirection(BlockFace.WEST);
		
		block.setTypeIdAndData(Material.WALL_SIGN.getId(), signData.getData(), false);
		
		sign = (org.bukkit.block.Sign)block.getState();

		sign.setLine(0, "<--");
		sign.setLine(1, "Region:");
		sign.setLine(2, regionName);
		sign.setLine(3, "<--");
		
		sign.update();
		
		//if teleportation is enabled, also add a sign facing north for teleportation help
		if(PopulationDensity.instance.allowTeleportation)
		{
			block = PopulationDensity.ManagedWorld.getBlockAt(x - 1, y + 3, z);
			
			signData = new org.bukkit.material.Sign(Material.WALL_SIGN);
			signData.setFacingDirection(BlockFace.WEST);
			
			block.setTypeIdAndData(Material.WALL_SIGN.getId(), signData.getData(), false);
			
			sign = (org.bukkit.block.Sign)block.getState();
			
			sign.setLine(0, "Z tąd możesz się");
			sign.setLine(1, "teleportować");
			sign.setLine(2, "Walnij mnie po");
			sign.setLine(3, "więcej informacji.");
			
			sign.update();
		}
		
		//add a sign for the region to the south
		regionName = this.getRegionName(new RegionCoordinates(region.x, region.z + 1));
		if(regionName == null) regionName = "Dzicz";
		regionName = PopulationDensity.capitalize(regionName);
		
		block = PopulationDensity.ManagedWorld.getBlockAt(x + 1, y + 2, z);
		
		signData = new org.bukkit.material.Sign(Material.WALL_SIGN);
		signData.setFacingDirection(BlockFace.EAST);
		
		block.setTypeIdAndData(Material.WALL_SIGN.getId(), signData.getData(), false);
		
		sign = (org.bukkit.block.Sign)block.getState();

		sign.setLine(0, "<--");
		sign.setLine(1, "Region:");
		sign.setLine(2, regionName);
		sign.setLine(3, "<--");
		
		sign.update();
		
		//if teleportation is enabled, also add a sign facing south for teleportation help
		if(PopulationDensity.instance.allowTeleportation)
		{
			block = PopulationDensity.ManagedWorld.getBlockAt(x + 1, y + 3, z);
			signData = new org.bukkit.material.Sign(Material.WALL_SIGN);
			signData.setFacingDirection(BlockFace.EAST);
			
			block.setTypeIdAndData(Material.WALL_SIGN.getId(), signData.getData(), false);
			
			sign = (org.bukkit.block.Sign)block.getState();

			sign.setLine(0, "Z tąd możesz się");
			sign.setLine(1, "teleportować");
			sign.setLine(2, "Walnij mnie po");
			sign.setLine(3, "więcej informacji.");
			
			sign.update();
		}
		
		//add a sign for the region to the north
		regionName = this.getRegionName(new RegionCoordinates(region.x - 1, region.z));
		if(regionName == null) regionName = "Dzicz";
		regionName = PopulationDensity.capitalize(regionName);
		
		block = PopulationDensity.ManagedWorld.getBlockAt(x, y + 2, z + 1);
		
		signData = new org.bukkit.material.Sign(Material.WALL_SIGN);
		signData.setFacingDirection(BlockFace.SOUTH);
		
		block.setTypeIdAndData(Material.WALL_SIGN.getId(), signData.getData(), false);
		
		sign = (org.bukkit.block.Sign)block.getState();

		sign.setLine(0, "<--");
		sign.setLine(1, "Region:");
		sign.setLine(2, regionName);
		sign.setLine(3, "<--");
		
		sign.update();
		
		//if teleportation is enabled, also add a sign facing west for /newestregion
		if(PopulationDensity.instance.allowTeleportation && !this.openRegionCoordinates.equals(region))
		{
			//block = PopulationDensity.ManagedWorld.getBlockAt(x, y + 3, z + 1);

			//signData = new org.bukkit.material.Sign(Material.WALL_SIGN);
			//signData.setFacingDirection(BlockFace.SOUTH);
			
			//block.setTypeIdAndData(Material.WALL_SIGN.getId(), signData.getData(), false);

			//sign = (org.bukkit.block.Sign)block.getState();
			
			//sign.update();
		}
		
		//custom signs
		
		if(PopulationDensity.instance.mainCustomSignContent != null)
		{
			block = PopulationDensity.ManagedWorld.getBlockAt(x, y + 3, z - 1);

			signData = new org.bukkit.material.Sign(Material.WALL_SIGN);
			signData.setFacingDirection(BlockFace.NORTH);
			
			block.setTypeIdAndData(Material.WALL_SIGN.getId(), signData.getData(), false);
			
			sign = (org.bukkit.block.Sign)block.getState();
			
			for(int i = 0; i < 4; i++)
			{
				sign.setLine(i, PopulationDensity.instance.mainCustomSignContent[i]);
			}
			
			sign.update();
		}
		
		if(PopulationDensity.instance.northCustomSignContent != null)
		{
			block = PopulationDensity.ManagedWorld.getBlockAt(x - 1, y + 1, z);

			signData = new org.bukkit.material.Sign(Material.WALL_SIGN);
			signData.setFacingDirection(BlockFace.WEST);
			
			block.setTypeIdAndData(Material.WALL_SIGN.getId(), signData.getData(), false);
			
			sign = (org.bukkit.block.Sign)block.getState();
			
			for(int i = 0; i < 4; i++)
			{
				sign.setLine(i, PopulationDensity.instance.northCustomSignContent[i]);
			}
			
			sign.update();
		}
		
		if(PopulationDensity.instance.southCustomSignContent != null)
		{
			block = PopulationDensity.ManagedWorld.getBlockAt(x + 1, y + 1, z);
			
			signData = new org.bukkit.material.Sign(Material.WALL_SIGN);
			signData.setFacingDirection(BlockFace.EAST);
			
			block.setTypeIdAndData(Material.WALL_SIGN.getId(), signData.getData(), false);
			
			sign = (org.bukkit.block.Sign)block.getState();
			
			for(int i = 0; i < 4; i++)
			{
				sign.setLine(i, PopulationDensity.instance.southCustomSignContent[i]);
			}
			
			sign.update();
		}
		
		if(PopulationDensity.instance.eastCustomSignContent != null)
		{
			block = PopulationDensity.ManagedWorld.getBlockAt(x, y + 1, z - 1);
			
			signData = new org.bukkit.material.Sign(Material.WALL_SIGN);
			signData.setFacingDirection(BlockFace.NORTH);
			
			block.setTypeIdAndData(Material.WALL_SIGN.getId(), signData.getData(), false);
			
			sign = (org.bukkit.block.Sign)block.getState();
			
			for(int i = 0; i < 4; i++)
			{
				sign.setLine(i, PopulationDensity.instance.eastCustomSignContent[i]);
			}
			
			sign.update();
		}
		
		if(PopulationDensity.instance.westCustomSignContent != null)
		{
			block = PopulationDensity.ManagedWorld.getBlockAt(x, y + 1, z + 1);
			
			signData = new org.bukkit.material.Sign(Material.WALL_SIGN);
			signData.setFacingDirection(BlockFace.SOUTH);
			
			block.setTypeIdAndData(Material.WALL_SIGN.getId(), signData.getData(), false);
			
			sign = (org.bukkit.block.Sign)block.getState();
			
			for(int i = 0; i < 4; i++)
			{
				sign.setLine(i, PopulationDensity.instance.westCustomSignContent[i]);
			}
			
			sign.update();
		}
	}
	
	public void clearCachedPlayerData(Player player)
	{
		this.playerNameToPlayerDataMap.remove(player.getName());		
	}
	
	private void loadMessages() 
    {
        Messages [] messageIDs = Messages.values();
        this.messages = new String[Messages.values().length];
        
        HashMap<String, CustomizableMessage> defaults = new HashMap<String, CustomizableMessage>();
        
        //initialize defaults
        this.addDefault(defaults, Messages.NoManagedWorld, "Plugin PopulationDensity nie został pomyślnie skonfigurowany.  Uaktualnij plik config.yml żeby określić świat do zarządzania.", null);
        this.addDefault(defaults, Messages.NoBreakPost, "Nie możesz niszczyć bloków w pobliżu spawnu regionu", null);
        this.addDefault(defaults, Messages.NoBreakSpawn, "Nie możesz niszczyć bloków w pobliżu spawnu innego gracza.", null);
        this.addDefault(defaults, Messages.NoBuildPost, "Nie możesz kłaść bloków w pobliżu spawnu regionu.", null);
        this.addDefault(defaults, Messages.NoBuildSpawn, "Nie możesz kłaść bloków w pobliżu spawnu innego gracza.", null);
        this.addDefault(defaults, Messages.HelpMessage1, "Pomoc oraz komendy dla regionów: {0} ", "0: Help URL");
        this.addDefault(defaults, Messages.BuildingAwayFromHome, "Budujesz poza swoim domyślnym regionem.  Jeśli chciałbyś żeby to był twój domyślny region, użyj /przeprowadzka.", null);
        this.addDefault(defaults, Messages.NoTeleportThisWorld, "Nie możesz teleportować się z tego świata.", null);
        this.addDefault(defaults, Messages.OnlyHomeCityHere, "W tym miejscu możesz używać tylko komend /dom oraz /spawn.", null);
        this.addDefault(defaults, Messages.NoTeleportHere, "Wybacz, nie możesz się z tąd teleportować. Udaj się na spawn regionu!", null);
        this.addDefault(defaults, Messages.NotCloseToPost, "Nie jesteś dosyć blisko spawnu regionu, żeby móc się teleportować.", null);
        this.addDefault(defaults, Messages.InvitationNeeded, "{0} żyje w dziczy. Żeby się do niego dostać najpierw musi Cię zaprosić (/zapros <twoj-nick>).", "0: target player");
        this.addDefault(defaults, Messages.VisitConfirmation, "Teleportowano do regionu gracza {0}", "0: target player");
        this.addDefault(defaults, Messages.DestinationNotFound, "Niema regionu, ani gracza o nazwie {0}.  Użyj /ListRegions żeby zobaczyć dostępne regiony..", "0: specified destination");
        this.addDefault(defaults, Messages.NeedNewestRegionPermission, "Nie masz uprawnień do tej komendy.", null);
        this.addDefault(defaults, Messages.NewestRegionConfirmation, "Teleportowano do najświeższego regionu.", null);
        this.addDefault(defaults, Messages.NotInRegion, "Nie znajdujesz się w żadnym regionie!", null);
        this.addDefault(defaults, Messages.UnnamedRegion, "Jesteś w dziczy!  Ten region nie ma jeszcze nazwy.", null);
        this.addDefault(defaults, Messages.WhichRegion, "Jesteś w regionie {0}", null);
        this.addDefault(defaults, Messages.RegionNamesNoSpaces, "Nazwa regionu nie może zawierać spacji.", null);
        this.addDefault(defaults, Messages.RegionNameLength, "Nazwa regionu musi posiadać przynajmniej {0} znaków.", "0: maximum length specified in config.yml");
        this.addDefault(defaults, Messages.RegionNamesOnlyLettersAndNumbers, "Nazwy regionów nie mogą zawierać symboli lub znaków interpunkcyjnych.", null);
        this.addDefault(defaults, Messages.RegionNameConflict, "Już istnieje region o takiej nazwie.", null);
        this.addDefault(defaults, Messages.NoMoreRegions, "Wybacz ale obecnie jesteś w jedynym dostępnym regionie. Później dostępne staną się kolejne regiony.", null);
        this.addDefault(defaults, Messages.InviteAlreadySent, "{0} może teraz użyć komendy /odwiedz {1} żeby teleportować się na spawn twojego regionu.", "0: invitee's name, 1: inviter's name");
        this.addDefault(defaults, Messages.InviteConfirmation, "{0} może teraz użyć komendy /odwiedz {1} żeby teleportować się na spawn twojego regionu.", "0: invitee's name, 1: inviter's name");
        this.addDefault(defaults, Messages.InviteNotification, "{0} zaprosił cię, żebyś go odwiedził!", "0: inviter's name");
        this.addDefault(defaults, Messages.InviteInstruction, "Użyj komendy /odwiedz {0}, żeby się tam teleportować.", "0: inviter's name");
        this.addDefault(defaults, Messages.PlayerNotFound, "Nie ma gracza o nicku {0} teraz na serwerze.", "0: specified name");
        this.addDefault(defaults, Messages.SetHomeConfirmation, "Ustawiono dom na spawn najbliżeszego regionu!", null);
        this.addDefault(defaults, Messages.SetHomeInstruction1, "Użyj /dom z dowolnego spawnu regionu żeby teleportować się do swojego domu.", null);
        this.addDefault(defaults, Messages.SetHomeInstruction2, "Użyj /zaproś <nick>, żeby zaprosić graczy do spawnu twojego regionu.", null);
        this.addDefault(defaults, Messages.AddRegionConfirmation, "Otwarto nowy region oraz rozpoczęto skanowanie jego zasobów. Zobacz konsolę lub logi serwera po więcej informacji.", null);
        this.addDefault(defaults, Messages.ScanStartConfirmation, "Rozpoczęto skanowanie zasobów. Zobacz konsolę lub logi serwera po więcej informacji.", null);
        this.addDefault(defaults, Messages.LoginPriorityCheck, "Priorytet logowania dla gracza {0} wynosi: {1}.", "0: player name, 1: current priority");
        this.addDefault(defaults, Messages.LoginPriorityUpdate, "Ustawiono pryiorytet logowania gracza {0} na: {1}.", "0: target player, 1: new priority");
        this.addDefault(defaults, Messages.ThinningConfirmation, "Thinning running.  Check logs for detailed results.", null);
        this.addDefault(defaults, Messages.PerformanceScore, "Obecny wynik wydajności serwera wynosi {0}%.", "0: performance score");
        this.addDefault(defaults, Messages.PerformanceScore_Lag, "  Serwer aktywnie pracuje teraz nad zmniejszeniem lagowania - proszę czekać, aż proces dobiegnie końca.", null);
        this.addDefault(defaults, Messages.PerformanceScore_NoLag, "Serwer działa teraz z normalną prędkością. Jeśli doświadczasz lagów sprawdź swoje ustawienia graficzne lub połączenie internetowe.  ", null);
        this.addDefault(defaults, Messages.PlayerMoved, "Gracz przeniesiony.", null);
        this.addDefault(defaults, Messages.Lag, "lag", null);
        this.addDefault(defaults, Messages.RegionAlreadyNamed, "Ten region już posiada nazwę. Żeby zmienić nazwę użyj komendy /RenameRegion.", null);
        this.addDefault(defaults, Messages.HopperLimitReached, "Żeby zapobiec laggom serwera, lejki (hoppery) są ograniczone do {0} na chunk.", "0: maximum hoppers per chunk");
        
        //load the config file
        FileConfiguration config = YamlConfiguration.loadConfiguration(new File(messagesFilePath));
        
        //for each message ID
        for(int i = 0; i < messageIDs.length; i++)
        {
            //get default for this message
            Messages messageID = messageIDs[i];
            CustomizableMessage messageData = defaults.get(messageID.name());
            
            //if default is missing, log an error and use some fake data for now so that the plugin can run
            if(messageData == null)
            {
                PopulationDensity.AddLogEntry("Brakująca wiadomość dla " + messageID.name() + ".  Skontaktuj się z developerem.");
                messageData = new CustomizableMessage(messageID, "Brakująca wiadomość!  ID: " + messageID.name() + ".  Skontaktuj się z administratorem serwera.", null);
            }
            
            //read the message from the file, use default if necessary
            this.messages[messageID.ordinal()] = config.getString("Messages." + messageID.name() + ".Text", messageData.text);
            config.set("Messages." + messageID.name() + ".Text", this.messages[messageID.ordinal()]);
            
            if(messageData.notes != null)
            {
                messageData.notes = config.getString("Messages." + messageID.name() + ".Notes", messageData.notes);
                config.set("Messages." + messageID.name() + ".Notes", messageData.notes);
            }
        }
        
        //save any changes
        try
        {
            config.save(DataStore.messagesFilePath);
        }
        catch(IOException exception)
        {
            PopulationDensity.AddLogEntry("Nie mogłem zapisać danych do pliku konfiguracyjnego \"" + DataStore.messagesFilePath + "\"");
        }
        
        defaults.clear();
        System.gc();                
    }

    private void addDefault(HashMap<String, CustomizableMessage> defaults, Messages id, String text, String notes)
    {
        CustomizableMessage message = new CustomizableMessage(id, text, notes);
        defaults.put(id.name(), message);       
    }

    synchronized public String getMessage(Messages messageID, String... args)
    {
        String message = messages[messageID.ordinal()];
        
        for(int i = 0; i < args.length; i++)
        {
            String param = args[i];
            message = message.replace("{" + i + "}", param);
        }
        
        return message;     
    }
	
	//list of region names to use
	private String [] regionNamesList;

    String getRegionNames()
    {
        StringBuilder builder = new StringBuilder();
        for(String regionName : this.nameToCoordsMap.keySet())
        {
            builder.append(PopulationDensity.capitalize(regionName)).append(", ");
        }
        
        return builder.toString();
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) throws IllegalArgumentException
    {
        Validate.notNull(sender, "Sender cannot be null");
        Validate.notNull(args, "Arguments cannot be null");
        Validate.notNull(alias, "Alias cannot be null");
        if (args.length == 0)
        {
                return ImmutableList.of();
        }
        
        StringBuilder builder = new StringBuilder();
        for(String arg : args)
        {
            builder.append(arg + " ");
        }
        
        String arg = builder.toString().trim();
        ArrayList<String> matches = new ArrayList<String>();
        for (String name : this.coordsToNameMap.values())
        {
            if (StringUtil.startsWithIgnoreCase(name, arg))
            {
                matches.add(name);
            }
        }
        
        Player senderPlayer = sender instanceof Player ? (Player) sender : null;
        for(Player player : sender.getServer().getOnlinePlayers())
        {
            if(senderPlayer == null || senderPlayer.canSee(player))
            {
                if(StringUtil.startsWithIgnoreCase(player.getName(), arg))
                {
                    matches.add(player.getName());
                }
            }
        }
        
        Collections.sort(matches, String.CASE_INSENSITIVE_ORDER);
        return matches;
    }
}
