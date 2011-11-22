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
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;
import org.dynmap.markers.Marker;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import com.bekvon.bukkit.residence.protection.ResidencePermissions;

public class DynmapResidencePlugin extends JavaPlugin {
    private static final Logger log = Logger.getLogger("Minecraft");
    private static final String LOG_PREFIX = "[dynmap-residence] ";
    private static final String DEF_INFOWINDOW = "<div class=\"infowindow\"><span style=\"font-size:120%;\">%regionname%</span><br /> Owner <span style=\"font-weight:bold;\">%playerowners%</span><br />Flags<br /><span style=\"font-weight:bold;\">%flags%</span></div>";
    DynmapAPI api;
    MarkerAPI markerapi;
    Residence res;
    ResidenceManager resmgr;
    
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
        double strokeopacity;
        int strokeweight;
        String fillcolor;
        double fillopacity;

        AreaStyle(FileConfiguration cfg, String path, AreaStyle def) {
            strokecolor = cfg.getString(path+".strokeColor", def.strokecolor);
            strokeopacity = cfg.getDouble(path+".strokeOpacity", def.strokeopacity);
            strokeweight = cfg.getInt(path+".strokeWeight", def.strokeweight);
            fillcolor = cfg.getString(path+".fillColor", def.fillcolor);
            fillopacity = cfg.getDouble(path+".fillOpacity", def.fillopacity);
        }

        AreaStyle(FileConfiguration cfg, String path) {
            strokecolor = cfg.getString(path+".strokeColor", "#FF0000");
            strokeopacity = cfg.getDouble(path+".strokeOpacity", 0.8);
            strokeweight = cfg.getInt(path+".strokeWeight", 3);
            fillcolor = cfg.getString(path+".fillColor", "#FF0000");
            fillopacity = cfg.getDouble(path+".fillOpacity", 0.35);
        }
    }
    
    public static void info(String msg) {
        log.log(Level.INFO, LOG_PREFIX + msg);
    }
    public static void severe(String msg) {
        log.log(Level.SEVERE, LOG_PREFIX + msg);
    }

    private class ResidenceUpdate implements Runnable {
        public void run() {
            updateResidence();
        }
    }
    
    private Map<String, AreaMarker> resareas = new HashMap<String, AreaMarker>();

    private String formatInfoWindow(String resid, ClaimedResidence res) {
        String v = "<div class=\"regioninfo\">"+infowindow+"</div>";
        v = v.replaceAll("%regionname%", res.getName());
        v = v.replaceAll("%playerowners%", res.getOwner());
        v = v.replaceAll("%flags%", res.getPermissions().listFlags());
        return v;
    }
    
    private boolean isVisible(String id) {
        if((visible != null) && (visible.size() > 0)) {
            if(visible.contains(id) == false) {
                return false;
            }
        }
        if((hidden != null) && (hidden.size() > 0)) {
            if(hidden.contains(id))
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
            sc = Integer.parseInt(as.strokecolor.substring(1), 16);
            fc = Integer.parseInt(as.fillcolor.substring(1), 16);
        } catch (NumberFormatException nfx) {
        }
        m.setLineStyle(as.strokeweight, as.strokeopacity, sc);
        m.setFillStyle(as.fillopacity, fc);
    }
    
    /* Handle specific residence */
    private void handleResidence(String resid, ClaimedResidence res, Map<String, AreaMarker> newmap, int depth) {
        String name = res.getName();
        double[] x = new double[4];
        double[] z = new double[4];
        
        /* Build popup */
        String desc = formatInfoWindow(resid, res);
        
        /* Handle cubiod areas */
        if(isVisible(resid)) {
            CuboidArea[] areas = res.getAreaArray();
            for(int i = 0; i < areas.length; i++) {
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
                    m = set.createAreaMarker(id, name, false, areas[i].getWorld().getName(), x, z, false);
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
 
        /* Loop through residences */
        String[] resids = resmgr.getResidenceList();
        for(String resid : resids) {
            /*TODO: check if in include list, or not in exclude list */
            ClaimedResidence res = resmgr.getByName(resid);
            if(res == null) continue;
            /* Handle residence */
            handleResidence(resid, res, newmap, 1);
        }
        /* Now, review old map - anything left is gone */
        for(AreaMarker oldm : resareas.values()) {
            oldm.deleteMarker();
        }
        /* And replace with new map */
        resareas = newmap;
        
        getServer().getScheduler().scheduleSyncDelayedTask(this, new ResidenceUpdate(), updperiod);
        
    }
    
    public void onEnable() {
    	info("initializing");
        Plugin p = this.getServer().getPluginManager().getPlugin("dynmap"); /* Find dynmap */
        if(p == null) {
            severe("Error finding dynmap!");
            return;
        }
        if(!p.isEnabled()) {	/* Make sure it's enabled before us */
        	getServer().getPluginManager().enablePlugin(p);
        	if(!p.isEnabled()) {
        		severe("Failed to enable dynmap!");
        		return;
        	}
        }
        api = (DynmapAPI)p; /* Get API */
        /* Now, get markers API */
        markerapi = api.getMarkerAPI();
        if(markerapi == null) {
            severe("Error loading dynmap marker API!");
            return;
        }
        /* Find residence */
        p = this.getServer().getPluginManager().getPlugin("Residence"); /* Find residence */
        if(p == null) {
            severe("Error loading Residence");
            return;
        }
        if(!p.isEnabled()) {	/* Make sure it's enabled before us */
        	getServer().getPluginManager().enablePlugin(p);
        	if(!p.isEnabled()) {
        		severe("Failed to enable Residence!");
        		return;
        	}
        }
        res = (Residence)p;
        
        resmgr = res.getResidenceManager(); /* Get residence manager */
        
        /* Load configuration */
        FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */
        
        /* Now, add marker set for mobs (make it transient) */
        set = markerapi.createMarkerSet("residence.markerset", cfg.getString("layer.name", "Residence"), null, false);
        if(set == null) {
            severe("Error creating marker set");
            return;
        }
        set.setLayerPriority(cfg.getInt("layer.layerprio", 10));
        set.setHideByDefault(cfg.getBoolean("layer.hidebydefault", false));
        use3d = cfg.getBoolean("use3dregions", false);
        maxdepth = cfg.getInt("resdepth", 2);
        if(maxdepth < 1) maxdepth = 1;
        infowindow = cfg.getString("infowindow", DEF_INFOWINDOW);
        
        /* Get style information */
        defstyle = new AreaStyle(cfg, "regionstyle");
        cusstyle = new HashMap<String, AreaStyle>();
        ConfigurationSection sect = cfg.getConfigurationSection("custstyle");
        if(sect != null) {
            Set<String> ids = sect.getKeys(false);
            
            for(String id : ids) {
                cusstyle.put(id, new AreaStyle(cfg, "custstyle." + id, defstyle));
            }
        }
        List vis = cfg.getList("visibleregions");
        if(vis != null) {
            visible = new HashSet<String>(vis);
        }
        List hid = cfg.getList("hiddenregions");
        if(hid != null) {
            hidden = new HashSet<String>(hid);
        }

        /* Set up update job - based on periond */
        int per = cfg.getInt("update.period", 300);
        if(per < 15) per = 15;
        updperiod = (long)(per*20);
        
        getServer().getScheduler().scheduleSyncDelayedTask(this, new ResidenceUpdate(), 40);   /* First time is 2 seconds */
        
        info("version " + this.getDescription().getVersion() + " is enabled");
    }

    public void onDisable() {
        if(set != null) {
            set.deleteMarkerSet();
            set = null;
        }
        resareas.clear();
    }

}
