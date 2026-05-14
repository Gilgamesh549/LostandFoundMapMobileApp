package com.example.lostandfoundmapmobileapp;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.ComponentActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private static final String PREFS_NAME = "lost_found_items";
    private static final String ITEMS_KEY = "items_json";
    private static final int LOCATION_FOR_HOME = 1;
    private static final int LOCATION_FOR_FORM = 2;

    private View homePanel;
    private View formPanel;
    private View mapPanel;
    private TextView homeLocationText;
    private TextView formCoordinateText;
    private TextView mapStatusText;
    private LinearLayout itemListContainer;
    private WebView mapWebView;
    private EditText radiusEditText;
    private EditText nameEditText;
    private EditText phoneEditText;
    private EditText descriptionEditText;
    private EditText dateEditText;
    private AutoCompleteTextView locationAutoCompleteText;
    private RadioButton lostRadioButton;

    private final ArrayList<LostFoundItem> items = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final ArrayList<AddressChoice> addressChoices = new ArrayList<>();
    private Runnable pendingAddressSearch;
    private ArrayAdapter<String> locationAdapter;
    private Geocoder geocoder;
    private Location currentLocation;
    private double selectedLatitude = Double.NaN;
    private double selectedLongitude = Double.NaN;
    private int pendingLocationTarget = LOCATION_FOR_HOME;
    private boolean updatingLocationText;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean fineGranted = result.get(Manifest.permission.ACCESS_FINE_LOCATION);
                Boolean coarseGranted = result.get(Manifest.permission.ACCESS_COARSE_LOCATION);
                if (Boolean.TRUE.equals(fineGranted) || Boolean.TRUE.equals(coarseGranted)) {
                    readCurrentLocation(pendingLocationTarget);
                } else {
                    Toast.makeText(this, "Location permission is required for current location.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        geocoder = new Geocoder(this, Locale.getDefault());
        bindViews();
        configureLocationAutocomplete();
        configureMapWebView();
        loadItems();
        bindActions();
        showHome();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdownNow();
    }

    private void bindViews() {
        homePanel = findViewById(R.id.homePanel);
        formPanel = findViewById(R.id.formPanel);
        mapPanel = findViewById(R.id.mapPanel);
        homeLocationText = findViewById(R.id.homeLocationText);
        formCoordinateText = findViewById(R.id.formCoordinateText);
        mapStatusText = findViewById(R.id.mapStatusText);
        itemListContainer = findViewById(R.id.itemListContainer);
        mapWebView = findViewById(R.id.mapWebView);
        radiusEditText = findViewById(R.id.radiusEditText);
        nameEditText = findViewById(R.id.nameEditText);
        phoneEditText = findViewById(R.id.phoneEditText);
        descriptionEditText = findViewById(R.id.descriptionEditText);
        dateEditText = findViewById(R.id.dateEditText);
        locationAutoCompleteText = findViewById(R.id.locationAutoCompleteText);
        lostRadioButton = findViewById(R.id.lostRadioButton);
    }

    private void bindActions() {
        Button createAdvertButton = findViewById(R.id.createAdvertButton);
        Button homeCurrentLocationButton = findViewById(R.id.homeCurrentLocationButton);
        Button showMapButton = findViewById(R.id.showMapButton);
        Button formCurrentLocationButton = findViewById(R.id.formCurrentLocationButton);
        Button saveAdvertButton = findViewById(R.id.saveAdvertButton);
        Button backFromFormButton = findViewById(R.id.backFromFormButton);
        Button backFromMapButton = findViewById(R.id.backFromMapButton);

        createAdvertButton.setOnClickListener(v -> showForm());
        homeCurrentLocationButton.setOnClickListener(v -> requestCurrentLocation(LOCATION_FOR_HOME));
        showMapButton.setOnClickListener(v -> showMap());
        formCurrentLocationButton.setOnClickListener(v -> requestCurrentLocation(LOCATION_FOR_FORM));
        saveAdvertButton.setOnClickListener(v -> saveAdvert());
        backFromFormButton.setOnClickListener(v -> showHome());
        backFromMapButton.setOnClickListener(v -> showHome());
    }

    private void configureLocationAutocomplete() {
        locationAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, new ArrayList<>());
        locationAutoCompleteText.setAdapter(locationAdapter);
        locationAutoCompleteText.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < addressChoices.size()) {
                AddressChoice choice = addressChoices.get(position);
                setLocationTextWithoutClearingCoordinates(choice.label);
                selectedLatitude = choice.latitude;
                selectedLongitude = choice.longitude;
                updateFormCoordinateText();
            }
        });
        locationAutoCompleteText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (updatingLocationText) {
                    return;
                }
                String query = s.toString().trim();
                selectedLatitude = Double.NaN;
                selectedLongitude = Double.NaN;
                updateFormCoordinateText();
                if (query.length() >= 3) {
                    if (pendingAddressSearch != null) {
                        mainHandler.removeCallbacks(pendingAddressSearch);
                    }
                    pendingAddressSearch = () -> searchAddresses(query);
                    mainHandler.postDelayed(pendingAddressSearch, 500);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void configureMapWebView() {
        WebSettings settings = mapWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
    }

    private void searchAddresses(String query) {
        executorService.execute(() -> {
            ArrayList<AddressChoice> choices = new ArrayList<>();
            try {
                List<Address> addresses = geocoder.getFromLocationName(query, 5);
                if (addresses != null) {
                    for (Address address : addresses) {
                        if (address.hasLatitude() && address.hasLongitude()) {
                            String label = buildAddressLabel(address);
                            choices.add(new AddressChoice(label, address.getLatitude(), address.getLongitude()));
                        }
                    }
                }
            } catch (IOException ignored) {
            }
            mainHandler.post(() -> updateAddressChoices(choices));
        });
    }

    private void updateAddressChoices(ArrayList<AddressChoice> choices) {
        addressChoices.clear();
        addressChoices.addAll(choices);
        locationAdapter.clear();
        for (AddressChoice choice : choices) {
            locationAdapter.add(choice.label);
        }
        locationAdapter.notifyDataSetChanged();
        if (!choices.isEmpty()) {
            locationAutoCompleteText.showDropDown();
        }
    }

    private String buildAddressLabel(Address address) {
        if (address.getMaxAddressLineIndex() >= 0) {
            return address.getAddressLine(0);
        }
        String locality = safe(address.getLocality());
        String country = safe(address.getCountryName());
        String fallback = (locality + " " + country).trim();
        return fallback.isEmpty() ? "Selected location" : fallback;
    }

    private void requestCurrentLocation(int target) {
        pendingLocationTarget = target;
        if (hasLocationPermission()) {
            readCurrentLocation(target);
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void readCurrentLocation(int target) {
        Location location = getBestLastKnownLocation();
        if (location == null) {
            requestSingleLocationUpdate(target);
            return;
        }
        handleCurrentLocation(location, target);
    }

    private void handleCurrentLocation(Location location, int target) {
        currentLocation = location;
        homeLocationText.setText(String.format(Locale.US, "Current location: %.5f, %.5f", location.getLatitude(), location.getLongitude()));
        if (target == LOCATION_FOR_FORM) {
            selectedLatitude = location.getLatitude();
            selectedLongitude = location.getLongitude();
            reverseGeocodeForForm(location);
            updateFormCoordinateText();
        }
    }

    private void requestSingleLocationUpdate(int target) {
        if (!hasLocationPermission()) {
            return;
        }
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        String provider = null;
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            provider = LocationManager.GPS_PROVIDER;
        } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            provider = LocationManager.NETWORK_PROVIDER;
        }
        if (provider == null) {
            Toast.makeText(this, "Please enable location services on the device or emulator.", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Waiting for current location...", Toast.LENGTH_SHORT).show();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.getCurrentLocation(provider, null, getMainExecutor(), location -> {
                    if (location == null) {
                        Toast.makeText(this, "Current location is not available yet.", Toast.LENGTH_LONG).show();
                    } else {
                        handleCurrentLocation(location, target);
                    }
                });
            } else {
                LocationListener listener = new LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        handleCurrentLocation(location, target);
                        locationManager.removeUpdates(this);
                    }
                };
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            }
        } catch (SecurityException ignored) {
        }
    }

    private Location getBestLastKnownLocation() {
        if (!hasLocationPermission()) {
            return null;
        }
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        try {
            for (String provider : locationManager.getProviders(true)) {
                Location location = locationManager.getLastKnownLocation(provider);
                if (location != null && (best == null || location.getAccuracy() < best.getAccuracy())) {
                    best = location;
                }
            }
        } catch (SecurityException ignored) {
        }
        return best;
    }

    private void reverseGeocodeForForm(Location location) {
        executorService.execute(() -> {
            String label = String.format(Locale.US, "%.5f, %.5f", location.getLatitude(), location.getLongitude());
            try {
                List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                if (addresses != null && !addresses.isEmpty()) {
                    label = buildAddressLabel(addresses.get(0));
                }
            } catch (IOException ignored) {
            }
            String finalLabel = label;
            mainHandler.post(() -> setLocationTextWithoutClearingCoordinates(finalLabel));
        });
    }

    private void saveAdvert() {
        String name = nameEditText.getText().toString().trim();
        String phone = phoneEditText.getText().toString().trim();
        String description = descriptionEditText.getText().toString().trim();
        String date = dateEditText.getText().toString().trim();
        String locationLabel = locationAutoCompleteText.getText().toString().trim();

        if (name.isEmpty() || phone.isEmpty() || description.isEmpty() || date.isEmpty() || locationLabel.isEmpty()) {
            Toast.makeText(this, "Please complete all advert fields.", Toast.LENGTH_SHORT).show();
            return;
        }

        String type = lostRadioButton.isChecked() ? "Lost" : "Found";
        if (Double.isNaN(selectedLatitude) || Double.isNaN(selectedLongitude)) {
            findCoordinatesThenSave(type, name, phone, description, date, locationLabel);
            return;
        }

        saveAdvertWithCoordinates(type, name, phone, description, date, locationLabel, selectedLatitude, selectedLongitude);
    }

    private void findCoordinatesThenSave(String type, String name, String phone, String description, String date, String locationLabel) {
        Toast.makeText(this, "Finding location coordinates...", Toast.LENGTH_SHORT).show();
        executorService.execute(() -> {
            Address matchedAddress = null;
            try {
                List<Address> addresses = geocoder.getFromLocationName(locationLabel, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    if (address.hasLatitude() && address.hasLongitude()) {
                        matchedAddress = address;
                    }
                }
            } catch (IOException ignored) {
            }

            Address finalMatchedAddress = matchedAddress;
            mainHandler.post(() -> {
                if (finalMatchedAddress == null) {
                    Toast.makeText(this, "Location coordinates were not found. Try a more specific address or use current location.", Toast.LENGTH_LONG).show();
                    return;
                }

                double latitude = finalMatchedAddress.getLatitude();
                double longitude = finalMatchedAddress.getLongitude();
                selectedLatitude = latitude;
                selectedLongitude = longitude;
                String resolvedLabel = buildAddressLabel(finalMatchedAddress);
                setLocationTextWithoutClearingCoordinates(resolvedLabel);
                updateFormCoordinateText();
                saveAdvertWithCoordinates(type, name, phone, description, date, resolvedLabel, latitude, longitude);
            });
        });
    }

    private void saveAdvertWithCoordinates(String type, String name, String phone, String description, String date,
                                           String locationLabel, double latitude, double longitude) {
        items.add(new LostFoundItem(type, name, phone, description, date, locationLabel, latitude, longitude));
        saveItems();
        clearForm();
        renderItemList();
        showHome();
        Toast.makeText(this, "Advert saved.", Toast.LENGTH_SHORT).show();
    }

    private void setLocationTextWithoutClearingCoordinates(String locationText) {
        updatingLocationText = true;
        try {
            locationAutoCompleteText.setText(locationText, false);
        } finally {
            updatingLocationText = false;
        }
    }

    private void clearForm() {
        lostRadioButton.setChecked(true);
        nameEditText.setText("");
        phoneEditText.setText("");
        descriptionEditText.setText("");
        dateEditText.setText("");
        locationAutoCompleteText.setText("");
        selectedLatitude = Double.NaN;
        selectedLongitude = Double.NaN;
        updateFormCoordinateText();
    }

    private void showHome() {
        homePanel.setVisibility(View.VISIBLE);
        formPanel.setVisibility(View.GONE);
        mapPanel.setVisibility(View.GONE);
        renderItemList();
    }

    private void showForm() {
        homePanel.setVisibility(View.GONE);
        formPanel.setVisibility(View.VISIBLE);
        mapPanel.setVisibility(View.GONE);
        updateFormCoordinateText();
    }

    private void showMap() {
        homePanel.setVisibility(View.GONE);
        formPanel.setVisibility(View.GONE);
        mapPanel.setVisibility(View.VISIBLE);

        double radiusKm = parseRadiusKm();
        ArrayList<LostFoundItem> visibleItems = filterItemsByRadius(radiusKm);
        String status;
        if (currentLocation != null && radiusKm > 0) {
            status = String.format(Locale.US, "Showing %d of %d items within %.1f km", visibleItems.size(), items.size(), radiusKm);
        } else if (radiusKm > 0) {
            status = "Set current location to apply radius search. Showing all items.";
        } else {
            status = String.format(Locale.US, "Showing all %d items", visibleItems.size());
        }
        mapStatusText.setText(status);
        mapWebView.loadDataWithBaseURL("https://localhost/", buildMapHtml(visibleItems, radiusKm), "text/html", "UTF-8", null);
    }

    private double parseRadiusKm() {
        String value = radiusEditText.getText().toString().trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private ArrayList<LostFoundItem> filterItemsByRadius(double radiusKm) {
        ArrayList<LostFoundItem> filtered = new ArrayList<>();
        if (radiusKm <= 0 || currentLocation == null) {
            filtered.addAll(items);
            return filtered;
        }
        for (LostFoundItem item : items) {
            float[] distance = new float[1];
            Location.distanceBetween(currentLocation.getLatitude(), currentLocation.getLongitude(), item.latitude, item.longitude, distance);
            if (distance[0] / 1000.0 <= radiusKm) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private void renderItemList() {
        itemListContainer.removeAllViews();
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No adverts saved yet.");
            empty.setTextColor(0xFF5D6D7E);
            empty.setTextSize(15);
            itemListContainer.addView(empty);
            return;
        }
        for (LostFoundItem item : items) {
            TextView row = new TextView(this);
            row.setText(String.format(Locale.US, "%s: %s\n%s\n%s", item.type, item.name, item.locationLabel, item.date));
            row.setTextColor(0xFF17202A);
            row.setTextSize(15);
            row.setPadding(0, 12, 0, 12);
            itemListContainer.addView(row);
        }
    }

    private void updateFormCoordinateText() {
        if (Double.isNaN(selectedLatitude) || Double.isNaN(selectedLongitude)) {
            formCoordinateText.setText("No coordinates selected");
        } else {
            formCoordinateText.setText(String.format(Locale.US, "Coordinates: %.5f, %.5f", selectedLatitude, selectedLongitude));
        }
    }

    private void loadItems() {
        items.clear();
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = preferences.getString(ITEMS_KEY, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                items.add(LostFoundItem.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
        }
    }

    private void saveItems() {
        JSONArray array = new JSONArray();
        for (LostFoundItem item : items) {
            array.put(item.toJson());
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(ITEMS_KEY, array.toString())
                .apply();
    }

    private String buildMapHtml(List<LostFoundItem> visibleItems, double radiusKm) {
        double centerLat = currentLocation != null ? currentLocation.getLatitude() : -37.8136;
        double centerLng = currentLocation != null ? currentLocation.getLongitude() : 144.9631;
        if (currentLocation == null && !visibleItems.isEmpty()) {
            centerLat = visibleItems.get(0).latitude;
            centerLng = visibleItems.get(0).longitude;
        }

        StringBuilder markers = new StringBuilder();
        for (LostFoundItem item : visibleItems) {
            markers.append("L.marker([")
                    .append(item.latitude)
                    .append(",")
                    .append(item.longitude)
                    .append("]).addTo(map).bindPopup('")
                    .append(jsEscape(item.type + ": " + item.name + "<br>" + item.locationLabel + "<br>" + item.phone))
                    .append("');\n");
        }
        if (currentLocation != null) {
            markers.append("L.circleMarker([")
                    .append(currentLocation.getLatitude())
                    .append(",")
                    .append(currentLocation.getLongitude())
                    .append("], {radius: 9, color: '#1f77b4', fillColor: '#1f77b4', fillOpacity: 0.85})")
                    .append(".addTo(map).bindPopup('Your current location');\n");
            if (radiusKm > 0) {
                markers.append("L.circle([")
                        .append(currentLocation.getLatitude())
                        .append(",")
                        .append(currentLocation.getLongitude())
                        .append("], {radius: ")
                        .append(radiusKm * 1000)
                        .append(", color: '#1f77b4', fillOpacity: 0.08}).addTo(map);\n");
            }
        }

        return "<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>"
                + "<style>html,body,#map{height:100%;margin:0;} .leaflet-popup-content{font:14px sans-serif;}</style>"
                + "</head><body><div id='map'></div>"
                + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>"
                + "<script>"
                + "var map=L.map('map').setView([" + centerLat + "," + centerLng + "], 13);"
                + "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'OpenStreetMap'}).addTo(map);"
                + markers
                + "var group=new L.featureGroup(); map.eachLayer(function(layer){if(layer instanceof L.Marker || layer instanceof L.CircleMarker || layer instanceof L.Circle){group.addLayer(layer);}});"
                + "if(group.getLayers().length>0){map.fitBounds(group.getBounds().pad(0.25));}"
                + "</script></body></html>";
    }

    private String jsEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "<br>");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static class AddressChoice {
        final String label;
        final double latitude;
        final double longitude;

        AddressChoice(String label, double latitude, double longitude) {
            this.label = label;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static class LostFoundItem {
        final String type;
        final String name;
        final String phone;
        final String description;
        final String date;
        final String locationLabel;
        final double latitude;
        final double longitude;

        LostFoundItem(String type, String name, String phone, String description, String date,
                      String locationLabel, double latitude, double longitude) {
            this.type = type;
            this.name = name;
            this.phone = phone;
            this.description = description;
            this.date = date;
            this.locationLabel = locationLabel;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("type", type);
                object.put("name", name);
                object.put("phone", phone);
                object.put("description", description);
                object.put("date", date);
                object.put("locationLabel", locationLabel);
                object.put("latitude", latitude);
                object.put("longitude", longitude);
            } catch (JSONException ignored) {
            }
            return object;
        }

        static LostFoundItem fromJson(JSONObject object) {
            return new LostFoundItem(
                    object.optString("type"),
                    object.optString("name"),
                    object.optString("phone"),
                    object.optString("description"),
                    object.optString("date"),
                    object.optString("locationLabel"),
                    object.optDouble("latitude"),
                    object.optDouble("longitude")
            );
        }
    }
}
