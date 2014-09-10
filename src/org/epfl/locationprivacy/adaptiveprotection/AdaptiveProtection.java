package org.epfl.locationprivacy.adaptiveprotection;

import java.util.ArrayList;
import java.util.Random;

import org.epfl.locationprivacy.map.databases.GridDBDataSource;
import org.epfl.locationprivacy.map.databases.VenuesCondensedDBDataSource;
import org.epfl.locationprivacy.map.models.MyPolygon;
import org.epfl.locationprivacy.privacyestimation.PrivacyEstimator;
import org.epfl.locationprivacy.privacyprofile.databases.SemanticLocationsDataSource;
import org.epfl.locationprivacy.util.Utils;

import android.content.Context;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient;
import com.google.android.gms.location.LocationClient;
import com.google.android.gms.maps.model.LatLng;

public class AdaptiveProtection implements AdaptiveProtectionInterface,
		GooglePlayServicesClient.ConnectionCallbacks,
		GooglePlayServicesClient.OnConnectionFailedListener {

	private static final String LOGTAG = "AdaptiveProtection";

	private static final double THETA = 0.2; //200 meters
	private static final int ALPHA = 2; // try 2 different obf regions with same size before enlarging the obf region
	private static final int MAX_OBF_REG_AREA = 81; // 81 grid cells (9X9)

	private PrivacyEstimator privacyEstimator;
	private Context context;
	private LocationClient locationClient;
	private Random random;
	private long totalLoggingTime;

	// All the next variables which have the prefix log* are only used by ThirdPartyActivity for testing purposes, because
	// the library returns only the obfuscation region. So, these variables can be removed safely without affecting the 
	// adaptive protection mechanism
	public static LatLng logCurrentLocation;
	public static MyPolygon logVenue;
	public static String logVenueDistance;
	public static Double logSensitivity;
	public static Double logPrivacyEstimation;
	public static String logObfRegSize;
	public static ArrayList<MyPolygon> logObfRegion;

	public AdaptiveProtection(Context context) {
		super();
		this.context = context;
		this.privacyEstimator = new PrivacyEstimator(context);
		this.locationClient = new LocationClient(context, this, this);
		locationClient.connect();
		random = new Random();
	}

	@Override
	public Pair<LatLng, LatLng> getObfuscationLocation() {
		// get current location
		Location location = locationClient.getLastLocation();
		if (location == null) {
			Toast.makeText(context, "Location is NULL", Toast.LENGTH_SHORT).show();
			return null;
		}

		return getObfuscationLocation(new LatLng(location.getLatitude(), location.getLongitude()));
	}

	@Override
	public Pair<LatLng, LatLng> getObfuscationLocation(LatLng location) {

		// Logging
		long startGetLocationTimeStamp = System.currentTimeMillis();
		totalLoggingTime = 0;
		log("================================");
		log("Current Location : " + location.latitude + "," + location.longitude);
		logCurrentLocation = location;

		// DBs preparation
		GridDBDataSource gridDBDataSource = GridDBDataSource.getInstance(context);
		VenuesCondensedDBDataSource venuesCondensedDBDataSource = VenuesCondensedDBDataSource
				.getInstance(context);
		SemanticLocationsDataSource semanticLocationsDataSource = SemanticLocationsDataSource
				.getInstance(context);

		//===========================================================================================
		//current Location ID
		long start = System.currentTimeMillis();
		MyPolygon currLocGridCell = gridDBDataSource.findGridCell(location.latitude,
				location.longitude);
		int fineLocationID = Integer.parseInt(currLocGridCell.getName());
		log("Getting fine Location ID took: " + (System.currentTimeMillis() - start) + " ms");
		log("Current CellID: " + fineLocationID);

		//===========================================================================================
		// getting sensitivity preference of location, if not existing then sensitivity of semantic of nearest venue
		Double sensitivity = currLocGridCell.getSensitivityAsDouble();
		log("Current Cell Sensitivity: " + sensitivity);
		if (sensitivity == null) { // current grid cell is not sensitive,then get nearest venue semantic sensitivity 

			//--> get semantics of current location
			long startGetNearVenues = System.currentTimeMillis();
			ArrayList<MyPolygon> currentLocationVenues = venuesCondensedDBDataSource
					.findVenuesContainingLocation(location.latitude, location.longitude);
			String semantic = null;
			if (!currentLocationVenues.isEmpty()) {
				semantic = currentLocationVenues.get(0).getSemantic();
				logVenue = currentLocationVenues.get(0);
				logVenueDistance = "inside";
			}

			//--> what if no venues contains the current location ? get the nearest location
			if (currentLocationVenues.isEmpty()) {
				Pair<MyPolygon, Double> nearestVenueAndDistance = venuesCondensedDBDataSource
						.findNearestVenue(location.latitude, location.longitude);
				semantic = nearestVenueAndDistance.first.getSemantic();
				logVenue = nearestVenueAndDistance.first;
				logVenueDistance = "nearest";
			}

			//--> get user sensitivity of current location semantic
			sensitivity = semanticLocationsDataSource.findSemanticSensitivity(semantic);

			//--> logging
			log("No Location Sensitivity");
			log("Nearest Venue: " + logVenue.getName());
			log("Relationship: " + logVenueDistance);
			log("Nearest Venue Query took " + (System.currentTimeMillis() - startGetNearVenues)
					+ " ms");
			log("Semantic: " + semantic);
			log("Semantic Sensitivity: " + sensitivity);
		}
		logSensitivity = sensitivity;

		log("Theta =  " + THETA);
		log("Theta * Sen =  " + (THETA * sensitivity));

		//===========================================================================================
		boolean finished = false;
		int lamda = 0;
		int ObfRegionHeightCells = getObfRegionHeightCells(lamda);
		int ObfRegionWidthCells = getObfRegionWidthCells(lamda);
		int numOfTrialsWithSameObfSize = 0;
		ArrayList<Integer> obfRegionCellIDs = null;
		while (!finished) {

			log("----------------------------");
			long startLoopTime = System.currentTimeMillis();

			//--> Phase 1: 
			// Generate obfuscation Region
			obfRegionCellIDs = generateRandomObfRegion(fineLocationID, ObfRegionHeightCells,
					ObfRegionWidthCells);
			log("Lamda: " + lamda);
			log("ObfRegionSize: " + ObfRegionWidthCells + "X" + ObfRegionHeightCells);
			logObfRegSize = ObfRegionWidthCells + "X" + ObfRegionHeightCells;

			//--> Phase 2: 
			// Get feedback from the privacy estimator
			long timeStamp = System.currentTimeMillis();
			double privacyEstimation = privacyEstimator.calculatePrivacyEstimation(location,
					fineLocationID, obfRegionCellIDs, timeStamp);
			log("Expected Distorition = " + privacyEstimation);

			//--> Phase3:
			// Comparison
			if (privacyEstimation > (THETA * sensitivity)) {
				finished = true;
			} else {
				numOfTrialsWithSameObfSize++;
				if (numOfTrialsWithSameObfSize >= ALPHA) {
					numOfTrialsWithSameObfSize = 0;
					lamda++;
					ObfRegionHeightCells = getObfRegionHeightCells(lamda);
					ObfRegionWidthCells = getObfRegionWidthCells(lamda);
				}
				if (ObfRegionHeightCells * ObfRegionWidthCells > MAX_OBF_REG_AREA) {
					finished = true;
					log("Terminating because obf region is too large ");
				}
			}

			//--> Phase4: 
			// update likability graph
			if (finished) {
				privacyEstimator.saveLastLinkabilityGraphCopy();
			}

			//--> Logging
			logPrivacyEstimation = privacyEstimation;
			log("This loop took: " + (System.currentTimeMillis() - startLoopTime) + " ms");
		}

		//===========================================================================================
		// The top left LngLat point is the first point of first gridcell
		int topLeftGridCellID = obfRegionCellIDs.get(0);
		LatLng obfRegionTopLeft = gridDBDataSource.findGridCell(topLeftGridCellID).getPoints()
				.get(0);

		// The bottom right LngLat point is the third point of the last gridcell
		int bottomRightGridCellID = obfRegionCellIDs.get(obfRegionCellIDs.size() - 1);
		LatLng obfRegtionBottomRight = gridDBDataSource.findGridCell(bottomRightGridCellID)
				.getPoints().get(2);

		//-->test
		logObfRegion = new ArrayList<MyPolygon>();
		for (int x : obfRegionCellIDs) {
			logObfRegion.add(GridDBDataSource.getInstance(context).findGridCell(x));
		}

		// Logging
		log("Total Adaptive Protection Time : "
				+ (System.currentTimeMillis() - startGetLocationTimeStamp) + " ms");
		log("Total logging time: " + totalLoggingTime + "ms");
		log("Total Adaptive Protection Time without logging : "
				+ (System.currentTimeMillis() - startGetLocationTimeStamp - totalLoggingTime)
				+ " ms");

		return new Pair<LatLng, LatLng>(obfRegionTopLeft, obfRegtionBottomRight);
	}

	private int getObfRegionWidthCells(int lamda) {
		//sx = 1+ Ceil(lamda/2)
		return 1 + (int) Math.ceil(lamda / 2.0);
	}

	private int getObfRegionHeightCells(int lamda) {
		//sy = 1+ Floor(lamda/2)
		return 1 + (int) Math.floor(lamda / 2.0);
	}

	private void log(String s) {
		long startlogging = System.currentTimeMillis();
		Log.d(LOGTAG, s);
		Utils.appendLog(LOGTAG+".txt", s);
		totalLoggingTime += (System.currentTimeMillis() - startlogging);
	}

	private ArrayList<Integer> generateRandomObfRegion(int fineLocationID,
			int obfRegionHeightCells, int obfRegionWidthCells) {
		ArrayList<Integer> obfRegionCellIDs = new ArrayList<Integer>();

		//curr row and col
		int currRow = fineLocationID / Utils.LAUSSANE_GRID_WIDTH_CELLS;
		int currCol = fineLocationID % Utils.LAUSSANE_GRID_WIDTH_CELLS;

		// top left cell id
		int topLeftRowDelta = random.nextInt(obfRegionHeightCells);
		int topLeftRow = currRow - topLeftRowDelta;
		topLeftRow = topLeftRow < 0 ? 0 : topLeftRow;

		int topLeftColDelta = random.nextInt(obfRegionWidthCells);
		int topLeftCol = currCol - topLeftColDelta;
		topLeftCol = topLeftCol < 0 ? 0 : topLeftCol;

		// bottom right cell id
		int bottomRightRow = topLeftRow + obfRegionHeightCells - 1;
		bottomRightRow = bottomRightRow >= Utils.LAUSSANE_GRID_HEIGHT_CELLS ? Utils.LAUSSANE_GRID_HEIGHT_CELLS - 1
				: bottomRightRow;

		int bottomRightCol = topLeftCol + obfRegionWidthCells - 1;
		bottomRightCol = bottomRightCol >= Utils.LAUSSANE_GRID_WIDTH_CELLS ? Utils.LAUSSANE_GRID_WIDTH_CELLS - 1
				: bottomRightCol;

		// generate cell ids
		for (int r = topLeftRow; r <= bottomRightRow; r++)
			for (int c = topLeftCol; c <= bottomRightCol; c++)
				obfRegionCellIDs.add(r * Utils.LAUSSANE_GRID_WIDTH_CELLS + c);
		log("Actual Location gridCellID: " + (topLeftColDelta + 1) + "X" + (topLeftRowDelta + 1));

		return obfRegionCellIDs;
	}

	//===========================================================================
	@Override
	public void onConnected(Bundle arg0) {
	}

	@Override
	public void onConnectionFailed(ConnectionResult arg0) {
	}

	@Override
	public void onDisconnected() {
	}
}
