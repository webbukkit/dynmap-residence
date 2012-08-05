package org.dynmap.residence;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.economy.TransactionManager;
import com.bekvon.bukkit.residence.economy.rent.RentManager;
import com.bekvon.bukkit.residence.event.ResidenceCreationEvent;
import com.bekvon.bukkit.residence.event.ResidenceDeleteEvent;
import com.bekvon.bukkit.residence.event.ResidenceFlagChangeEvent;
import com.bekvon.bukkit.residence.event.ResidenceOwnerChangeEvent;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import com.bekvon.bukkit.residence.protection.ResidencePermissions;

public class DynmapResidencePlugin extends JavaPlugin {
    private static final Logger log = Logger.getLogger("Minecraft");
    private static final String LOG_PREFIX = "[dynmap-residence] ";
    private static final String DEF_INFOWINDOW = "<div class=\"infowindow\"><span style=\"font-size:120%;\">%regionname%</span><br /> Owner <span style=\"font-weight:bold;\">%playerowners%</span><br />Flags<br /><span style=\"font-weight:bold;\">%flags%</span></div>";
    Plugin dynmap;
    DynmapAPI api;
    MarkerAPI markerapi;
    Residence res;
    ResidenceManager resmgr;
    RentManager rentmgr;
    TransactionManager transmgr;
    boolean stop;
    boolean reload = false;
    
    FileConfiguration cfg;
    MarkerSet set;
    long updperiod;
    boolean use3d;
    int maxdepth;
    String infowindow;
    AreaStyle defstyle;
    Map<String, AreaStyle> cusstyle;
    Set<String> visible;
    Set<String> hidden;
    
    private static class AreaStyle {
        String strokecolor;
        String forrentstrokecolor;
        String forsalestrokecolor;
        double strokeopacity;
        int strokeweight;
        String fillcolor;
        double fillopacity;
        int y;

        AreaStyle(FileConfiguration cfg, String path, AreaStyle def) {
            strokecolor = cfg.getString(path+".strokeColor", def.strokecolor);
            forrentstrokecolor = cfg.getString(path+".forRentStrokeColor", def.forrentstrokecolor);
            forsalestrokecolor = cfg.getString(path+".forSaleStrokeColor", def.forsalestrokecolor);
            strokeopacity = cfg.getDouble(path+".strokeOpacity", def.strokeopacity);
            strokeweight = cfg.getInt(path+".strokeWeight", def.strokeweight);
            fillcolor = cfg.getString(path+".fillColor", def.fillcolor);
            fillopacity = cfg.getDouble(path+".fillOpacity", def.fillopacity);
            y = cfg.getInt(path+".y", def.y);
        }

        AreaStyle(FileConfiguration cfg, String path) {
            strokecolor = cfg.getString(path+".strokeColor", "#FF0000");
            forrentstrokecolor = cfg.getString(path+".forRentStrokeColor", "#007F00");
            forsalestrokecolor = cfg.getString(path+".forSaleStrokeColor", "#007F7F");
            strokeopacity = cfg.getDouble(path+".strokeOpacity", 0.8);
            strokeweight = cfg.getInt(path+".strokeWeight", 3);
            fillcolor = cfg.getString(path+".fillColor", "#FF0000");
            fillopacity = cfg.getDouble(path+".fillOpacity", 0.35);
            y = cfg.getInt(path+".y", 64);
        }
    }
    
    public static void info(String msg) {
        log.log(Level.INFO, LOG_PREFIX + msg);
    }
    public static void severe(String msg) {
        log.log(Level.SEVERE, LOG_PREFIX + msg);
    }

    private class ResidenceUpdate implements Runnable {
    	public boolean repeat;
        public void run() {
            if(!stop) {
                updateResidence();
                if(repeat)
                	getServer().getScheduler().scheduleSyncDelayedTask(DynmapResidencePlugin.this, ResidenceUpdate.this, updperiod);
                else
                	pending_oneshot = null;
            }
        }
    }
    private ResidenceUpdate pending_oneshot = null;
    
    private Map<String, AreaMarker> resareas = new HashMap<String, AreaMarker>();

    private static final String FLAGS[] = { "use", "move", "build", "tp",
    	"ignite", "container", "subzone", "destroy", "place", "bucket", "bank",
    	"pvp", "damage", "monsters", "firespread", "tnt", "creeper",
    	"flow", "healing", "animals", "lavaflow", "waterflow", "physics",
    	"piston", "spread" };
    
    private String formatInfoWindow(String resid, ClaimedResidence res) {
        String v = "<div class=\"regioninfo\">"+infowindow+"</div>";
        v = v.replace("%regionname%", res.getName());
        v = v.replace("%playerowners%", res.getOwner());
        String m = res.getEnterMessage();
        v = v.replace("%entermsg%", (m!=null)?m:"" );
        m = res.getLeaveMessage();
        v = v.replace("%leavemsg%", (m!=null)?m:"");
        ResidencePermissions p = res.getPermissions();
        String flgs = "";
        for(int i = 0; i < FLAGS.length; i++) {
        	if(p.isSet(FLAGS[i])) {
        		if(flgs.length() > 0) flgs += "<br/>";
        		boolean f = p.has(FLAGS[i], false);
        		flgs += FLAGS[i] + ": " + f;
                v = v.replace("%flag."+FLAGS[i]+"%", Boolean.toString(f));
        	}
        	else
                v = v.replace("%flag."+FLAGS[i]+"%", "");

        }
        v = v.replace("%flags%", flgs);

        if(rentmgr != null) {
            boolean isrented = rentmgr.isRented(resid);
            boolean isforrent = rentmgr.isForRent(resid);
            v = v.replace("%isforrent%", Boolean.toString(isforrent));
            v = v.replace("%isrented%", Boolean.toString(isrented));
            String id = "";
            if(isrented)
                id = rentmgr.getRentingPlayer(resid);
            v = v.replace("%renter%", id);
            String rent = "";
            String rentdays = "";
            if(isforrent) {
                rent = Integer.toString(rentmgr.getCostOfRent(resid));
                rentdays = Integer.toString(rentmgr.getRentDays(resid));
            }
            v = v.replace("%rent%", rent);
            v = v.replace("%rentdays%", rentdays);
        }
        else {
            v = v.replace("%isforrent%", "");
            v = v.replace("%isrented%", "");
            v = v.replace("%renter%", "");
            v = v.replace("%rent%", "");
            v = v.replace("%rentdays%", "");
        }
        if(transmgr != null) {
            boolean forsale = transmgr.isForSale(resid);
            v = v.replace("%isforsale%", Boolean.toString(transmgr.isForSale(resid)));
            String price = "";
            if(forsale)
                price = Integer.toString(transmgr.getSaleAmount(resid));
            v = v.replace("%price%", price);
        }
        else {
            v = v.replace("%isforsale%", "");
            v = v.replace("%price%", "");
        }
        return v;
    }
    
    private boolean isVisible(String id, String worldname) {
        if((visible != null) && (visible.size() > 0)) {
            if((visible.contains(id) == false) && (visible.contains("world:" + worldname) == false)) {
                return false;
            }
        }
        if((hidden != null) && (hidden.size() > 0)) {
            if(hidden.contains(id) || hidden.contains("world:" + worldname))
                return false;
        }
        return true;
    }
        
    private void addStyle(String resid, AreaMarker m) {
        AreaStyle as = cusstyle.get(resid);
        if(as == null)
            as = defstyle;
        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            if((rentmgr != null) && rentmgr.isForRent(resid))
                sc = Integer.parseInt(as.forrentstrokecolor.substring(1), 16);
            else if((transmgr != null) && transmgr.isForSale(resid))
                sc = Integer.parseInt(as.forsalestrokecolor.substring(1), 16);
            else
                sc = Integer.parseInt(as.strokecolor.substring(1), 16);
            fc = Integer.parseInt(as.fillcolor.substring(1), 16);
        } catch (NumberFormatException nfx) {
        }
        m.setLineStyle(as.strokeweight, as.strokeopacity, sc);
        m.setFillStyle(as.fillopacity, fc);
        m.setRangeY(as.y, as.y);
    }
    
    /* Handle specific residence */
    private void handleResidence(String resid, ClaimedResidence res, Map<String, AreaMarker> newmap, int depth) {
        String name = res.getName();
        double[] x = new double[4];
        double[] z = new double[4];
        
        /* Build popup */
        String desc = formatInfoWindow(resid, res);
        
        /* Handle cubiod areas */
        CuboidArea[] areas = res.getAreaArray();
        for(int i = 0; i < areas.length; i++) {
            String wname = areas[i].getWorld().getName();
            if(isVisible(resid, wname) == false) continue;
            
            String id = resid + "%" + i;    /* Make area ID for cubiod */
            Location l0 = areas[i].getLowLoc();
            Location l1 = areas[i].getHighLoc();
            /* Make outline */
            x[0] = l0.getX(); z[0] = l0.getZ();
            x[1] = l0.getX(); z[1] = l1.getZ()+1.0;
            x[2] = l1.getX() + 1.0; z[2] = l1.getZ()+1.0;
            x[3] = l1.getX() + 1.0; z[3] = l0.getZ();
        
            AreaMarker m = resareas.remove(id); /* Existing area? */
            if(m == null) {
                m = set.createAreaMarker(id, name, false, wname, x, z, false);
                if(m == null) continue;
            }
            else {
                m.setCornerLocations(x, z); /* Replace corner locations */
                m.setLabel(name);   /* Update label */
            }
            if(use3d) { /* If 3D? */
                m.setRangeY(l1.getY()+1.0, l0.getY());
            }
            m.setDescription(desc); /* Set popup */
        
            /* Set line and fill properties */
            addStyle(resid, m);

            /* Add to map */
            newmap.put(id, m);
        }
        if(depth < maxdepth) {  /* If not at max, check subzones */
            String[] subids = res.listSubzones();
            for(int i = 0; i < subids.length; i++) {
                String id = resid + "." + subids[i];    /* Make ID for subzone */
                ClaimedResidence sub = res.getSubzone(subids[i]);
                if(sub == null) continue;
                /* Recurse into subzone */
                handleResidence(id, sub, newmap, depth+1);
            }
        }
    }
    
    /* Update residence information */
    private void updateResidence() {
        Map<String,AreaMarker> newmap = new HashMap<String,AreaMarker>(); /* Build new map */
 
        resmgr = Residence.getResidenceManager(); /* Get residence manager */
        rentmgr = Residence.getRentManager();
        transmgr = Residence.getTransactionManager();
        
        if(resmgr != null) {
            /* Loop through residences */
            String[] resids = resmgr.getResidenceList();
            for(String resid : resids) {
                ClaimedResidence res = resmgr.getByName(resid);
                if(res == null) continue;
                /* Handle residence */
                handleResidence(resid, res, newmap, 1);
            }
        }
        /* Now, review old map - anything left is gone */
        for(AreaMarker oldm : resareas.values()) {
            oldm.deleteMarker();
        }
        /* And replace with new map */
        resareas = newmap;        
    }

    private class OurServerListener implements Listener {
        @EventHandler(priority=EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin p = event.getPlugin();
            String name = p.getDescription().getName();
            if(name.equals("dynmap") || name.equals("Residence")) {
                if(dynmap.isEnabled() && res.isEnabled())
                    activate();
            }
        }
    }
    
    private class OurCustomEventListener implements Listener {
        private void fireUpdate() {
            if(pending_oneshot == null) {
                pending_oneshot = new ResidenceUpdate();
                getServer().getScheduler().scheduleSyncDelayedTask(DynmapResidencePlugin.this, pending_oneshot, 20);   /* Delay a second to let other triggers fire */
            }
        }
        @EventHandler(priority=EventPriority.MONITOR)
        public void onResidenceCreate(ResidenceCreationEvent event) {
            if(event.isCancelled()) return;
            fireUpdate();
        }
        @EventHandler(priority=EventPriority.MONITOR)
        public void onResidenceFlagChange(ResidenceFlagChangeEvent event) {
            if(event.isCancelled()) return;
            fireUpdate();
        }
        @EventHandler(priority=EventPriority.MONITOR)
        public void onResidenceDelete(ResidenceDeleteEvent event) {
            if(event.isCancelled()) return;
            fireUpdate();
        }
        @EventHandler(priority=EventPriority.MONITOR)
        public void onResidenceOwnerChange(ResidenceOwnerChangeEvent event) {
            fireUpdate();
        }
    }
    
    public void onEnable() {
        info("initializing");
        PluginManager pm = getServer().getPluginManager();
        /* Get dynmap */
        dynmap = pm.getPlugin("dynmap");
        if(dynmap == null) {
            severe("Cannot find dynmap!");
            return;
        }
        api = (DynmapAPI)dynmap; /* Get API */
        /* Get Residence */
        Plugin p = pm.getPlugin("Residence");
        if(p == null) {
            severe("Cannot find Residence!");
            return;
        }
        res = (Residence)p;

        getServer().getPluginManager().registerEvents(new OurServerListener(), this);        

        /* If both enabled, activate */
        if(dynmap.isEnabled() && res.isEnabled())
            activate();
    }

    private void activate() {
        /* Now, get markers API */
        markerapi = api.getMarkerAPI();
        if(markerapi == null) {
            severe("Error loading dynmap marker API!");
            return;
        }
        
        resmgr = Residence.getResidenceManager(); /* Get residence manager */
        
        /* Load configuration */
        if(reload) {
            reloadConfig();
        }
        else {
            reload = true;
        }
        FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */
        
        /* Now, add marker set for mobs (make it transient) */
        set = markerapi.getMarkerSet("residence.markerset");
        if(set == null)
            set = markerapi.createMarkerSet("residence.markerset", cfg.getString("layer.name", "Residence"), null, false);
        else
            set.setMarkerSetLabel(cfg.getString("layer.name", "Residence"));
        if(set == null) {
            severe("Error creating marker set");
            return;
        }
        set.setLayerPriority(cfg.getInt("layer.layerprio", 10));
        set.setHideByDefault(cfg.getBoolean("layer.hidebydefault", false));
        int minzoom = cfg.getInt("layer.minzoom", 0);
        if(minzoom > 0)
            set.setMinZoom(minzoom);
        use3d = cfg.getBoolean("use3dregions", false);
        maxdepth = cfg.getInt("resdepth", 2);
        if(maxdepth < 1) maxdepth = 1;
        infowindow = cfg.getString("infowindow", DEF_INFOWINDOW);
        
        /* Get style information */
        defstyle = new AreaStyle(cfg, "regionstyle");
        cusstyle = new HashMap<String, AreaStyle>();
        ConfigurationSection sect = cfg.getConfigurationSection("custstyle");
        if(sect != null) {
            Set<String> ids = sect.getKeys(true);
            
            for(String id : ids) {
                cusstyle.put(id, new AreaStyle(cfg, "custstyle." + id, defstyle));
            }
        }
        List<String> vis = cfg.getStringList("visibleregions");
        if(vis != null) {
            visible = new HashSet<String>(vis);
        }
        List<String> hid = cfg.getStringList("hiddenregions");
        if(hid != null) {
            hidden = new HashSet<String>(hid);
        }

        /* Set up update job - based on periond */
        int per = cfg.getInt("update.period", 300);
        if(per < 15) per = 15;
        updperiod = (long)(per*20);
        stop = false;
        
        ResidenceUpdate updater = new ResidenceUpdate();
        updater.repeat = true;
        getServer().getScheduler().scheduleSyncDelayedTask(this, updater, 40);   /* First time is 2 seconds */
        
        /* Register custom event listener - listen for residence change events */
        if(cfg.getBoolean("update.onchange", true))
        	getServer().getPluginManager().registerEvents(new OurCustomEventListener(), this);
        
        info("version " + this.getDescription().getVersion() + " is activated");
    }

    public void onDisable() {
        if(set != null) {
            set.deleteMarkerSet();
            set = null;
        }
        resareas.clear();
        stop = true;
    }

}
