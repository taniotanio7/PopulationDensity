package me.ryanhamshire.PopulationDensity;

import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;

public class AfkCheckTask implements Runnable 
{
	private Player player;
	private PlayerData playerData;
	
	public AfkCheckTask(Player player, PlayerData playerData)
	{
		this.player = player;
		this.playerData = playerData;
	}
	
	@Override
	public void run() 
	{
		if(!player.isOnline()) return;
		
		boolean kick = false;
		
		//if the player is and has been in a minecart, kick him
		if(player.getVehicle() instanceof Minecart)
		{
			 if(playerData.wasInMinecartLastRound)
			 {
				 kick = true;
			 }
			 
			 playerData.wasInMinecartLastRound = true;
		}
		
		else
		{
			playerData.wasInMinecartLastRound = false;
		}
		
		//if the player hasn't moved, kick him
		try
		{
			if(playerData.lastObservedLocation != null && (playerData.lastObservedLocation.distance(player.getLocation()) < 3))
			{
				kick = true;
			}
		}
		catch(IllegalArgumentException exception){}
		
		int playersOnline = PopulationDensity.instance.getServer().getOnlinePlayers().size();
		if(!player.hasPermission("populationdensity.idle") && kick &&
		   (PopulationDensity.bootingIdlePlayersForLag ||
		    PopulationDensity.instance.getServer().getMaxPlayers() - PopulationDensity.instance.reservedSlotsForAdmins - 3 <= playersOnline))
		{
			PopulationDensity.AddLogEntry("Wyrzucono " + player.getName() + " za bycie nieaktywnym.");
			player.kickPlayer("Wyrzucono za bycie nieaktywnym, żeby zrobić miejsce dla nowych graczy");
			return;
		}
		
		playerData.lastObservedLocation = player.getLocation();
		
		//otherwise, restart the timer for this task
		//20L ~ 1 second		
		playerData.afkCheckTaskID = PopulationDensity.instance.getServer().getScheduler().scheduleSyncDelayedTask(PopulationDensity.instance, this, 20L * 60 * PopulationDensity.instance.maxIdleMinutes);
	}
}
