package com.nutiteq.layers;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import android.net.Uri;

import com.nutiteq.components.Envelope;
import com.nutiteq.components.MapPos;
import com.nutiteq.components.MapPos;
import com.nutiteq.geometry.Polygon3D;
import com.nutiteq.log.Log;
import com.nutiteq.projections.Projection;
import com.nutiteq.style.Polygon3DStyle;
import com.nutiteq.style.StyleSet;
import com.nutiteq.ui.DefaultLabel;
import com.nutiteq.vectorlayers.Polygon3DLayer;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKBReader;

public class Polygon3DOSMLayer extends Polygon3DLayer {
    
    // this height modifier depends on map projection, and latitude: Default world 0.5f OpenGL units is very roughly 30m

    private static final float HEIGHT_ADJUST = 0.5f / 30.0f;
	
    // we use here a server with non-standard (and appareantly undocumented) API
    // it returns OSM building=yes polygons in WKB format, for given BBOX
	private String baseUrl = "http://kaart.maakaart.ee/poiexport/buildings2.php?";

	private StyleSet<Polygon3DStyle> styleSet;

	private int minZoom;
	private int maxObjects;
	private float height;

	/**
	 * Constructor for layer with 3D OSM building boxes
	 * 
	 * @param proj Projection, usually EPSG3857()
	 * @param height default height for buildings, unless they have height tag set
	 * @param maxObjects limits number of objects per network call. Btw, the server has "largest objects first" order
	 * @param styleSet defines visual styles
	 */
	public Polygon3DOSMLayer(Projection proj, float height, int maxObjects, StyleSet<Polygon3DStyle> styleSet) {
		super(proj);
		this.styleSet = styleSet;
		this.maxObjects = maxObjects;
		this.height = height;
		minZoom = styleSet.getFirstNonNullZoomStyleZoom();
	}

	@Override
	public void add(Polygon3D element) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void remove(Polygon3D element) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void calculateVisibleElements(Envelope envelope, int zoom) {
		if (zoom < minZoom) {
			visibleElementsList = null; 
			return;
		}
		
		// here we calculate bounding box, so it is in projection coordinates instead of internal 
		// coordinates of the envelope
		MapPos bottomLeft = projection.fromInternal((float) envelope.getMinX(), (float) envelope.getMinY());
		MapPos topRight = projection.fromInternal((float) envelope.getMaxX(), (float) envelope.getMaxY());

		List<String> names = new LinkedList<String>();
		List<String> heights = new LinkedList<String>();
		List<String> types = new LinkedList<String>();
		List<String> addresses = new LinkedList<String>();
		
		List<Polygon> visibleElementslist = loadGeom(bottomLeft.x, bottomLeft.y, topRight.x, topRight.y, maxObjects,
				baseUrl, names, heights, types, addresses);

		long start = System.currentTimeMillis();
		List<Polygon3D> newVisibleElementsList = new LinkedList<Polygon3D>();
		int j = 0;
		for (Polygon geometry : visibleElementslist) {
		    ArrayList<MapPos> mapPoses = new ArrayList<MapPos>();
			LineString outerRing = ((Polygon) geometry.norm()).getExteriorRing();
			
			for (Coordinate coordinate : outerRing.getCoordinates()) {
				mapPoses.add(new MapPos((float) coordinate.x, (float) coordinate.y));
			}

			ArrayList<List<MapPos>> mapPosesHoles = new ArrayList<List<MapPos>>();

			for (int i = 0; i < geometry.getNumInteriorRing(); i++) {
			    List<MapPos> mapPosHole = new ArrayList<MapPos>();
				LineString holeRing = ((Polygon) geometry.norm()).getInteriorRingN(i);
				holeRing.normalize();
				for (Coordinate coordinate : holeRing.getCoordinates()) {
					mapPosHole.add(new MapPos((float) coordinate.x, (float) coordinate.y));
				}
				mapPosesHoles.add(mapPosHole);
			}

			// parse address and name for label
			String name = null;
			String type = null;
			String address = null;
			
			if (names.get(j) != null && !names.get(j).equals("")) {
				name = names.get(j);
			}
			float h = this.height;
			
			// type for future use
            if (types.get(j) != null && !types.get(j).equals("")) {
                type = types.get(j);
            }
			
            if (addresses.get(j) != null && !addresses.get(j).equals("")) {
                address = addresses.get(j);
            }
            
            if (heights.get(j) != null && !heights.get(j).equals("")) {
                String heightStr = null;
                try {
                    heightStr = heights.get(j);
                    float hVal;
                    if(heightStr.contains(" ")){
                        String[] parts = heightStr.split(" ");
                        hVal = Float.parseFloat(parts[0]);
                        hVal = convertToMeters(hVal,parts[1]);
                    }else{
                        hVal = Float.parseFloat(heightStr);
                    }
                    h = HEIGHT_ADJUST * hVal;
                            
                    Log.debug("found real height ="+h+" from "+height);
                } catch (NumberFormatException e) {
                    Log.error("could not parse " + heightStr);
                }

            }
            
			// Log.debug("name = '" + name + "' address = '" + address + "'");
			DefaultLabel label = null;
			if (name == null && address != null) {
				label = new DefaultLabel(address);
			}
			if (name != null && address == null) {
				label = new DefaultLabel(name);
			}
			if (name != null && address != null) {
				label = new DefaultLabel(name, address);
			}

			// create polygon
			Polygon3D polygon3D = new Polygon3D(mapPoses, mapPosesHoles, h, label, styleSet, null);
			++j;
			try {
				polygon3D.calculateInternalPos(projection);
			}
			catch (RuntimeException e) {
				Log.error("Polygon3DOSMLayer: Failed to triangulate! " + e.getMessage());
				continue;
			}
			polygon3D.setActiveStyle(zoom);
			newVisibleElementsList.add(polygon3D);
		}
		Log.debug("Triangulation time: " + (System.currentTimeMillis() - start));

		visibleElementsList = newVisibleElementsList;
	}

	private float convertToMeters(float hVal, String unit) {
        if(unit.equals("m")){
            return hVal;
        }
        if(unit.equals("ft")){
            return hVal * 0.3048f;
        }
        if(unit.equals("yd")){
            return hVal * 0.9144f;
        }
        return hVal;
    }

    /**
     * Helper method to load geometries from the server
     */
    private List<Polygon> loadGeom(double x, double y, double x2, double y2, int maxObjects2, String baseUrl2,
			List<String> names, List<String> heights, List<String> types, List<String> addresses) {
		List<Polygon> objects = new LinkedList<Polygon>();
		// URL request format: http://kaart.maakaart.ee/poiexport/buildings2.php?bbox=xmin,ymin,xmax,ymax&output=wkb
		try {
			Uri.Builder uri = Uri.parse(baseUrl2).buildUpon();
			uri.appendQueryParameter("bbox", (int) x + "," + (int) y + "," + (int) x2 + "," + (int) y2);
			uri.appendQueryParameter("output", "wkb");
			uri.appendQueryParameter("max", String.valueOf(maxObjects2));
			Log.debug("url:" + uri.build().toString());

			URLConnection conn = new URL(uri.build().toString()).openConnection();
			DataInputStream data = new DataInputStream(new BufferedInputStream(conn.getInputStream()));
			int n = data.readInt();
			Log.debug("polygons:" + n);

			for (int i = 0; i < n; i++) {
				String name = data.readUTF();
				String height = data.readUTF();
				String type = data.readUTF();
				String address = data.readUTF();
				// Log.debug("name:" + name);
				// Log.debug("tags:" + tags);
				int len = data.readInt();
				byte[] wkb = new byte[len];
				data.read(wkb);
				Geometry geom = new WKBReader().read(wkb);
				Polygon poly = null;
                if (geom instanceof MultiPolygon){
                    for (int j=0; j<((MultiPolygon) geom).getNumGeometries();j++){
                        poly = (Polygon) geom.getGeometryN(j);
                        objects.add(poly);
                        names.add(name);
                        heights.add(height);
                        types.add(type);
                        addresses.add(address);
                    }
				    
				}else if(geom instanceof Polygon)
                    poly = (Polygon) geom;
                    objects.add(poly);
                    names.add(name);
                    heights.add(height);
                    types.add(type);
                    addresses.add(address);

			}

		}
		catch (IOException e) {
			Log.error("IO ERROR " + e.getMessage());
		} catch (ParseException e) {
            e.printStackTrace();
        }

		return objects;
	}
}
