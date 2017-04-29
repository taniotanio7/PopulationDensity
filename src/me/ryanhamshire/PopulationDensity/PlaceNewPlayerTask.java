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

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

//created during new player login to teleport that player to his home region after a short delay
class PlaceNewPlayerTask extends BukkitRunnable
{
	private PopulationDensity instance;
	private Player player;
	private RegionCoordinates region;
	
	public PlaceNewPlayerTask(Player player, RegionCoordinates region, PopulationDensity plugin)
	{
		this.player = player;
		this.region = region;
		this.instance = plugin;
	}
	
	@Override
	public void run()
	{
		instance.TeleportPlayerToRegion(player, region, 0, instance.config_launchAndDropNewPlayers);
	}
}
