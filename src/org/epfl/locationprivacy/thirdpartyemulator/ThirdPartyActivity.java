package org.epfl.locationprivacy.thirdpartyemulator;

import java.util.ArrayList;

import org.epfl.locationprivacy.R;
import org.epfl.locationprivacy.adaptiveprotection.AdaptiveProtection;
import org.epfl.locationprivacy.adaptiveprotection.AdaptiveProtectionInterface;
import org.epfl.locationprivacy.map.models.MyPolygon;
import org.epfl.locationprivacy.util.Utils;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.MapsInitializer;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;

public class ThirdPartyActivity extends Activity {

	private static final String LOGTAG = "ThirdPartyActivity";
	AdaptiveProtectionInterface adaptiveProtectionInterface;
	GoogleMap googleMap;
	MapView mapView;
	ArrayList<Polygon> polygons;
	ArrayList<Marker> markers;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Initialize adaptive protection
		adaptiveProtectionInterface = new AdaptiveProtection(this);

		// Initialize Google Maps
		if (Utils.googlePlayServicesOK(this)) {
			setContentView(R.layout.activity_thirdparty);
			mapView = (MapView) findViewById(R.id.thirdpartymapview);
			mapView.onCreate(savedInstanceState);

			if (initMap()) {
				MapsInitializer.initialize(this);
			} else {
				Toast.makeText(this, "Map not available", Toast.LENGTH_SHORT).show();
			}
		} else {
			Toast.makeText(this, "Google Play services Not OK", Toast.LENGTH_SHORT).show();
		}

		// Initialize Polygons
		polygons = new ArrayList<Polygon>();
		markers = new ArrayList<Marker>();
	}

	private boolean initMap() {
		googleMap = mapView.getMap();
		return googleMap != null;
	}

	//==================================================
	@Override
	protected void onDestroy() {
		super.onDestroy();
		mapView.onDestroy();
	}

	@Override
	public void onLowMemory() {
		super.onLowMemory();
		mapView.onLowMemory();
	}

	@Override
	protected void onPause() {
		super.onPause();
		mapView.onPause();
	}

	@Override
	protected void onResume() {
		super.onResume();
		mapView.onResume();
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		mapView.onSaveInstanceState(outState);
	}

	//==================================================
	public void emulateThirdParty(View view) {

		// Mock Data1
		ArrayList<LatLng> mockLocations = new ArrayList<LatLng>();

		if (view.getId() == R.id.thirdpartytestlocations) {
			// cell ids : 10367, 17609, 11003
			mockLocations.add(new LatLng(46.533114299838836, 6.573491469025612));
			mockLocations.add(new LatLng(46.522422470139205, 6.585019938647747));
			mockLocations.add(new LatLng(46.53251253519775, 6.595497317612171));
		} else if (view.getId() == R.id.thirdpartytestsemantics) {
			mockLocations.add(new LatLng(46.5192, 6.5661)); // university
			mockLocations.add(new LatLng(46.5212, 6.6320)); // bar
			mockLocations.add(new LatLng(46.5253, 6.6421)); // hospital
		}

		// Clean the previous experiment if exists
		Utils.removePolygons(polygons);
		Utils.removeMarkers(markers);

		//Create Logging folder
		Utils.createNewLoggingFolder();

		long startExperiment = System.currentTimeMillis();
		for (int index = 0; index < mockLocations.size(); index++) {

			//create logging folder for this location
			Utils.createNewLoggingSubFolder();

			// get location
			LatLng mockLocation = mockLocations.get(index);
			ArrayList<MyPolygon> obfRegionCells = adaptiveProtectionInterface
					.getLocation(mockLocation);

			//testing
			Log.d(LOGTAG, "polygons returned: " + obfRegionCells.size());
			for (MyPolygon obfRegionCell : obfRegionCells) {
				polygons.add(Utils.drawPolygon(obfRegionCell, googleMap, 0x33FF0000));
			}

			// adding marker for the current Location
			MarkerOptions markerOptions = new MarkerOptions()
					.title("CurrentLocation")
					.snippet("Privacy Estimation: " + AdaptiveProtection.logPrivacyEstimation)
					.position(
							new LatLng(AdaptiveProtection.logCurrentLocation.getLatitude(),
									AdaptiveProtection.logCurrentLocation.getLongitude()));
			markers.add(googleMap.addMarker(markerOptions));

			// draw nearest venue
			if (view.getId() == R.id.thirdpartytestsemantics) {
				polygons.add(Utils.drawPolygon(AdaptiveProtection.logVenue, googleMap, 0x5500FF00));
				MarkerOptions markerOptions2 = new MarkerOptions()
						.title("Name: " + AdaptiveProtection.logVenue.getName())
						.snippet(
								"Tag: " + AdaptiveProtection.logVenue.getSemantic()
										+ " Sensitivity: " + AdaptiveProtection.logSensitivity)
						.position(
								new LatLng(AdaptiveProtection.logVenue.getPoints().get(0).latitude,
										AdaptiveProtection.logVenue.getPoints().get(0).longitude));
				markers.add(googleMap.addMarker(markerOptions2));
			}

			//move camera
			//			CameraUpdate cameraUpdate = CameraUpdateFactory.newLatLngZoom(new LatLng(
			//					AdaptiveProtection.logCurrentLocation.getLatitude(),
			//					AdaptiveProtection.logCurrentLocation.getLongitude()), 15);
			//			googleMap.moveCamera(cameraUpdate);

			Log.d(LOGTAG, "Finished mock location number: " + (index + 1));
		}
		Toast.makeText(
				this,
				"Finished experiment in " + (System.currentTimeMillis() - startExperiment) / 1000
						+ " sec", Toast.LENGTH_SHORT).show();
	}
}
