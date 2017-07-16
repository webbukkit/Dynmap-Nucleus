package us.dynmap.dynmapnucleus;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.dynmap.DynmapCommonAPIListener;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerIcon;
import org.dynmap.markers.MarkerSet;
import org.dynmap.DynmapCommonAPI;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.asset.Asset;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.game.state.GameStartedServerEvent;
import org.spongepowered.api.event.game.state.GameStartingServerEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.plugin.Dependency;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.scheduler.SpongeExecutorService;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import com.google.common.reflect.TypeToken;
import com.google.inject.Inject;

import io.github.nucleuspowered.nucleus.api.NucleusAPI;
import io.github.nucleuspowered.nucleus.api.nucleusdata.Home;
import io.github.nucleuspowered.nucleus.api.nucleusdata.Warp;
import io.github.nucleuspowered.nucleus.api.service.NucleusHomeService;
import io.github.nucleuspowered.nucleus.api.service.NucleusWarpService;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;

@Plugin(id = "dynmap-nucleus", 
		name = "Dynmap-Nucleus",
		version = "0.1", 
		description = "Nucleus support for Dynmap", 
		authors = {"mikeprimm"},
		dependencies = { @Dependency(id = "nucleus"), @Dependency(id = "dynmap") } )
public class DynmapNucleus {

	@Inject private Logger logger;
	@Inject private PluginContainer plugin;
	@Inject @ConfigDir(sharedRoot = false) 
	private File configDir;
    @Inject @DefaultConfig(sharedRoot = false)
    private ConfigurationLoader<CommentedConfigurationNode> configManager;

	private MarkerAPI markerapi;
	private NucleusWarpService warp_api;
	private NucleusHomeService homes_api;
	private CommentedConfigurationNode cfg;
	
	private SpongeExecutorService execsrv;

	/* Homes layer settings */
    private Layer homelayer;
    
    /* Warps layer settings */
    private Layer warplayer;
    
    long updperiod;
    long playerupdperiod;
    boolean stop;
    boolean reload = false;

    private abstract class Layer {
        MarkerSet set;
        MarkerIcon deficon;
        String labelfmt;
        Set<String> visible;
        Set<String> hidden;
        Map<String, Marker> markers = new HashMap<String, Marker>();
        
        public Layer(String id, CommentedConfigurationNode cfg, String deflabel, String deficon, String deflabelfmt) {
            set = markerapi.getMarkerSet("nucleus." + id);
            CommentedConfigurationNode lcfg = cfg.getNode("layer", id);
            if(set == null)
                set = markerapi.createMarkerSet("nucleus."+id, lcfg.getNode("name").getString(deflabel), null, false);
            else
                set.setMarkerSetLabel(lcfg.getNode("name").getString(deflabel));
            if(set == null) {
                logger.error("Error creating " + deflabel + " marker set");
                return;
            }
            set.setLayerPriority(lcfg.getNode("layerprio").getInt(10));
            set.setHideByDefault(lcfg.getNode("hidebydefault").getBoolean(false));
            int minzoom = lcfg.getNode("minzoom").getInt(0);
            if(minzoom > 0) /* Don't call if non-default - lets us work with pre-0.28 dynmap */
                set.setMinZoom(minzoom);
            String icon = lcfg.getNode("deficon").getString(deficon);
            this.deficon = markerapi.getMarkerIcon(icon);
            if(this.deficon == null) {
                logger.info("Unable to load default icon '" + icon + "' - using default '"+deficon+"'");
                this.deficon = markerapi.getMarkerIcon(deficon);
            }
            labelfmt = lcfg.getNode("labelfmt").getString(deflabelfmt);
            List<String> lst;
			try {
				lst = lcfg.getNode("visiblemarkers").getList(TypeToken.of(String.class));
			} catch (ObjectMappingException e) {
				lst = null;
			}
            if(lst != null)
                visible = new HashSet<String>(lst);
            try {
				lst = lcfg.getNode("hiddenmarkers").getList(TypeToken.of(String.class));
			} catch (ObjectMappingException e) {
				lst = null;
			}
            if(lst != null)
                hidden = new HashSet<String>(lst);
        }
        
        void cleanup() {
            if(set != null) {
                set.deleteMarkerSet();
                set = null;
            }
            markers.clear();
        }
        
        boolean isVisible(String id, String wname) {
            if((visible != null) && (visible.isEmpty() == false)) {
                if((visible.contains(id) == false) && (visible.contains("world:" + wname) == false))
                    return false;
            }
            if((hidden != null) && (hidden.isEmpty() == false)) {
                if(hidden.contains(id) || hidden.contains("world:" + wname))
                    return false;
            }
            return true;
        }
        
        void updateMarkerSet() {
            Map<String, Marker> newmap = new HashMap<String, Marker>(); /* Build new map */
            
            Map<String,Location<World>> marks = getMarkers();
            for(String name: marks.keySet()) {
                Location<World> loc = marks.get(name);
                
                String wname = loc.getExtent().getName();
                /* Skip if not visible */
                if(isVisible(name, wname) == false)
                    continue;
                /* Get location */
                String id = wname + "/" + name;

                String label = labelfmt.replace("%name%", name);
                    
                /* See if we already have marker */
                Marker m = markers.remove(id);
                if(m == null) { /* Not found?  Need new one */
                    m = set.createMarker(id, label, wname, loc.getX(), loc.getY(), loc.getZ(), deficon, false);
                }
                else {  /* Else, update position if needed */
                    m.setLocation(wname, loc.getX(), loc.getY(), loc.getZ());
                    m.setLabel(label);
                    m.setMarkerIcon(deficon);
                }
                newmap.put(id, m);    /* Add to new map */
            }
            /* Now, review old map - anything left is gone */
            for(Marker oldm : markers.values()) {
                oldm.deleteMarker();
            }
            /* And replace with new map */
            markers.clear();
            markers = newmap;
        }
        /* Get current markers, by ID with location */
        public abstract Map<String,Location<World>> getMarkers();
    }

    private class WarpsLayer extends Layer {

        public WarpsLayer(CommentedConfigurationNode cfg, String fmt) {
            super("warps", cfg, "Warps", "portal", fmt);
        }
        /* Get current markers, by ID with location */
        public Map<String,Location<World>> getMarkers() {
            HashMap<String,Location<World>> map = new HashMap<String,Location<World>>();
            if(warp_api != null) {
            	List<Warp> wn = warp_api.getAllWarps();
                for(Warp n: wn) {
                	Location<World> loc = n.getLocation().orElse(null);
                	if (loc != null) {
                        map.put(n.getName(), loc);
                    }
                }
            }
            return map;
        }
    }
    
    private class HomesLayer extends Layer {
        boolean online_only = true;
        
        public HomesLayer(CommentedConfigurationNode cfg, String fmt) {
            super("homes", cfg, "Homes", "house", fmt);
            CommentedConfigurationNode lcfg = cfg.getNode("layer", "homes");
            //online_only = lcfg.getNode("online-only").getBoolean(false);
            if(online_only) {
                OurPlayerListener lsnr = new OurPlayerListener();
                Sponge.getEventManager().registerListeners(plugin, lsnr);
            }
        }
        /* Get current markers, by ID with location */
        public Map<String,Location<World>> getMarkers() {
            HashMap<String,Location<World>> map = new HashMap<String,Location<World>>();
            if(homes_api != null) {
            	Collection<Player> players = Sponge.getServer().getOnlinePlayers();                
            	
            	for (Player player : players) {
            		List<Home> hlist = homes_api.getHomes(player);
            		if (hlist == null) {
            			continue;
            		}
            		for (Home home : hlist) {
            			Location<World> loc = home.getLocation().orElse(null);
            			if (loc != null) {
            				if (home.getName().equals("home")) {
            					map.put(player.getName(), loc);
            				}
            				else {
            					map.put(player.getName() + ":" + home.getName(), loc);
            				}
            			}
                    }
                }
            }
            return map;
        }
    }

	public DynmapNucleus() {
	}
	
	public Logger getLogger(){
		return logger;
	}
	
	public File getConfigDirectory(){
		return configDir;
	}
	
	public PluginContainer getPlugin(){
		return plugin;
	}
	
	/**
	 * 
	 * All initializations for the plugin should be done here.
	 * 
	 * @param e GamePreInitializationEvent dispatched by Sponge.
	 */
	@Listener
	public void onGamePreInitialization(GamePreInitializationEvent e){
		getLogger().info("Enabling " + plugin.getName() + " version " + plugin.getVersion().get() + ".");
				
		// Load configuration
		loadConfig();
	}
	
	private void loadConfig() {
        Asset asset = plugin.getAsset("dynmap-nucleus.conf").orElse(null);
        Path configPath = configDir.toPath().resolve("dynmap-nucleus.conf");
        
        if (Files.notExists(configPath)) {
            if (asset != null) {
                try {
                    asset.copyToFile(configPath);
                } catch (IOException e) {
                    e.printStackTrace();
                    logger.error("Could not unpack the default config from the jar!");
                    return;
                }
            } else {
                logger.error("Could not find the default config file in the jar!");
                return;
            }
        }
        try {
            cfg = configManager.load();
        } catch (IOException e) {
            logger.error("An IOException occured while trying to load the config");
            return;
        }
	}
	
	@Listener
	public void onGameStartingServer(GameStartingServerEvent e) {
		// Register with Dynmap
		DynmapCommonAPIListener.register(new DynmapCommonAPIListener() {
			@Override
			public void apiEnabled(DynmapCommonAPI api) {
				markerapi = api.getMarkerAPI();
				activate();
			}
		});
		// Create sync executor service
		execsrv = Sponge.getScheduler().createSyncExecutor(plugin);
	}
	
    @Listener
    public void onGameStartedServer(GameStartedServerEvent e){
    }

    public class OurPlayerListener implements Runnable {
    	@Listener
    	public void onPlayerJoin(ClientConnectionEvent.Join event) {
    		execsrv.schedule(this, 500, TimeUnit.MILLISECONDS);
    	}
        @Listener
        public void onPlayerQuit(ClientConnectionEvent.Disconnect event) {
           	execsrv.schedule(this, 500, TimeUnit.MILLISECONDS);
        }
		public void run() {
			if((!stop) && (homes_api != null)) {
				homelayer.updateMarkerSet();
			}
		}
    }
    
    private class MarkerUpdate implements Runnable {
        public void run() {
            if(!stop)
                updateMarkers();
        }
    }

    /* Update mob population and position */
    private void updateMarkers() {
        if(homes_api != null) {
            homelayer.updateMarkerSet();
        }
        if(warp_api != null) {
            warplayer.updateMarkerSet();
        }
        execsrv.schedule(new MarkerUpdate(), updperiod * 50, TimeUnit.MILLISECONDS);
    }
    
    private void activate() {
    	logger.info("Activate Dynmap-Nucleus");
    	if (cfg == null) {
    		return;
    	}
    	warp_api = NucleusAPI.getWarpService().orElse(null);
    	homes_api = NucleusAPI.getHomeService().orElse(null);
        /* Load configuration */
        if(reload) {
            loadConfig();
            if(homelayer != null) {
                if(homelayer.set != null) {
                    homelayer.set.deleteMarkerSet();
                    homelayer.set = null;
                }
                homelayer = null;
            }
            if(warplayer != null) {
                if(warplayer.set != null) {
                    warplayer.set.deleteMarkerSet();
                    warplayer.set = null;
                }
                warplayer = null;
            }
        }
        else {
            reload = true;
        }
        /* Check which is enabled */
        if(cfg.getNode("layer", "homes", "enable").getBoolean(true) == false)
            homes_api = null;
        if(cfg.getNode("layer", "warps", "enable").getBoolean(true) == false)
            warp_api = null;
        
        /* Now, add marker set for homes */
        if(homes_api != null)
            homelayer = new HomesLayer(cfg, "%name%(home)");
        /* Now, add marker set for warps */
        if(warp_api != null)
            warplayer = new WarpsLayer(cfg, "[%name%]");
        
        /* Set up update job - based on period */
        double per = cfg.getNode("update","period").getDouble(5.0);
        if(per < 2.0) per = 2.0;
        updperiod = (long)(per*20.0);
        stop = false;
        execsrv.schedule(new MarkerUpdate(), 5, TimeUnit.SECONDS);
    }

    public void onDisable() {
        if(homelayer != null) {
            homelayer.cleanup();
            homelayer = null;
        }
        if(warplayer != null) {
            warplayer.cleanup();
            warplayer = null;
        }
        stop = true;
    }

}
	
