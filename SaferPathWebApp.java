import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class SafePathWebApp {
    static List<SafetyReport> safetyReports = new ArrayList<>();
    static Map<String, Location> erodeLocations = new HashMap<>();
    static Map<String, List<Route>> predefinedRoutes = new HashMap<>();
    static Map<String, NavigationSession> navigationSessions = new ConcurrentHashMap<>();
    static Map<String, UserLocation> userLocations = new ConcurrentHashMap<>();
    static Map<String, List<RouteSegment>> routeSegments = new ConcurrentHashMap<>();
    
    static GPSService gpsService = new GPSService();
    static SafetyAnalysisService safetyAnalysisService = new SafetyAnalysisService();
    
    static {
        initializeErodeLocations();
        initializeErodeRoutes();
        initializeSampleSafetyReports();
    }
    
    static class GPSService {
        private Map<String, GPSDevice> devices = new ConcurrentHashMap<>();
        private Random random = new Random();
        
        public GPSLocation getCurrentLocation(String deviceId) {
            if (!devices.containsKey(deviceId)) {
                devices.put(deviceId, new GPSDevice(deviceId));
            }
            GPSDevice device = devices.get(deviceId);
            return device.getCurrentLocation();
        }
        
        public GPSLocation simulateMovement(String deviceId, Location destination) {
            GPSDevice device = devices.get(deviceId);
            if (device != null) {
                return device.moveToward(destination);
            }
            return getCurrentLocation(deviceId);
        }
    }
    
    static class GPSDevice {
        private String deviceId;
        private double latitude;
        private double longitude;
        private double speed;
        private double bearing;
        private Random random;
        
        public GPSDevice(String deviceId) {
            this.deviceId = deviceId;
            this.random = new Random(deviceId.hashCode());
            this.latitude = 11.3410 + (random.nextDouble() * 0.1 - 0.05);
            this.longitude = 77.7172 + (random.nextDouble() * 0.1 - 0.05);
            this.speed = 0;
            this.bearing = 0;
        }
        
        public GPSLocation getCurrentLocation() {
            if (speed > 0) {
                double distance = (speed / 3600) * 5;
                double bearingRad = Math.toRadians(bearing);
                double latRad = Math.toRadians(latitude);
                double deltaLat = distance * Math.cos(bearingRad) / 111.32;
                double deltaLng = distance * Math.sin(bearingRad) / (111.32 * Math.cos(latRad));
                
                latitude += deltaLat;
                longitude += deltaLng;
            }
            return new GPSLocation(latitude, longitude, speed, bearing, LocalDateTime.now());
        }
        
        public GPSLocation moveToward(Location destination) {
            double destLat = destination.getLatitude();
            double destLng = destination.getLongitude();
            double dLng = Math.toRadians(destLng - longitude);
            double y = Math.sin(dLng) * Math.cos(Math.toRadians(destLat));
            double x = Math.cos(Math.toRadians(latitude)) * Math.sin(Math.toRadians(destLat)) -
                      Math.sin(Math.toRadians(latitude)) * Math.cos(Math.toRadians(destLat)) * Math.cos(dLng);
            bearing = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
            speed = Math.min(80, speed + 5);
            return getCurrentLocation();
        }
    }
    
    static class GPSLocation {
        private double latitude;
        private double longitude;
        private double speed;
        private double bearing;
        private LocalDateTime timestamp;
        private double accuracy;
        
        public GPSLocation(double latitude, double longitude, double speed, double bearing, LocalDateTime timestamp) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.speed = speed;
            this.bearing = bearing;
            this.timestamp = timestamp;
            this.accuracy = 5.0 + Math.random() * 15.0;
        }
        
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
        public double getSpeed() { return speed; }
        public double getBearing() { return bearing; }
        public LocalDateTime getTimestamp() { return timestamp; }
        public double getAccuracy() { return accuracy; }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("lat", latitude);
            map.put("lng", longitude);
            map.put("speed", speed);
            map.put("bearing", bearing);
            map.put("accuracy", accuracy);
            map.put("timestamp", timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            map.put("address", getApproximateAddress());
            return map;
        }
        
        private String getApproximateAddress() {
            return erodeLocations.values().stream()
                .min((a, b) -> Double.compare(
                    calculateDistance(latitude, longitude, a.getLatitude(), a.getLongitude()),
                    calculateDistance(latitude, longitude, b.getLatitude(), b.getLongitude())
                ))
                .map(loc -> "Near " + loc.getAddress())
                .orElse("Erode Region");
        }
        
        private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
            double dLat = Math.toRadians(lat2 - lat1);
            double dLng = Math.toRadians(lng2 - lng1);
            double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                      Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                      Math.sin(dLng/2) * Math.sin(dLng/2);
            double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
            return 6371 * c;
        }
    }
    
    static class UserLocation {
        private String userId;
        private GPSLocation currentLocation;
        private LocalDateTime lastUpdate;
        private boolean isNavigating;
        private String navigationSessionId;
        
        public UserLocation(String userId) {
            this.userId = userId;
            this.lastUpdate = LocalDateTime.now();
            this.isNavigating = false;
        }
        
        public void updateLocation(GPSLocation location) {
            this.currentLocation = location;
            this.lastUpdate = LocalDateTime.now();
        }
        
        public String getUserId() { return userId; }
        public GPSLocation getCurrentLocation() { return currentLocation; }
        public LocalDateTime getLastUpdate() { return lastUpdate; }
        public boolean isNavigating() { return isNavigating; }
        public void setNavigating(boolean navigating) { isNavigating = navigating; }
        public String getNavigationSessionId() { return navigationSessionId; }
        public void setNavigationSessionId(String sessionId) { this.navigationSessionId = sessionId; }
    }
    
    static class NavigationSession {
        String sessionId;
        String userId;
        Route route;
        List<RouteSegment> segments;
        int currentSegmentIndex;
        LocalDateTime startTime;
        boolean isActive;
        double progress;
        GPSLocation currentGPSLocation;
        List<GPSLocation> locationHistory;
        double distanceTraveled;
        List<String> spokenInstructions;
        
        public NavigationSession(String sessionId, String userId, Route route) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.route = route;
            this.segments = RouteSegment.createSegmentsFromRoute(route);
            this.currentSegmentIndex = 0;
            this.startTime = LocalDateTime.now();
            this.isActive = true;
            this.progress = 0.0;
            this.locationHistory = new ArrayList<>();
            this.distanceTraveled = 0.0;
            this.spokenInstructions = new ArrayList<>();
            
            this.currentGPSLocation = gpsService.getCurrentLocation(userId);
            this.locationHistory.add(currentGPSLocation);
            
            UserLocation userLoc = userLocations.computeIfAbsent(userId, UserLocation::new);
            userLoc.setNavigating(true);
            userLoc.setNavigationSessionId(sessionId);
            
            routeSegments.put(sessionId, segments);
        }
        
        public void updateGPSLocation() {
            GPSLocation newLocation;
            if (isActive && currentSegmentIndex < segments.size()) {
                RouteSegment currentSegment = segments.get(currentSegmentIndex);
                newLocation = gpsService.simulateMovement(userId, currentSegment.getEndPoint());
                
                double segmentProgress = calculateSegmentProgress(currentSegment);
                if (segmentProgress >= 0.95 && currentSegmentIndex < segments.size() - 1) {
                    currentSegmentIndex++;
                }
            } else {
                newLocation = gpsService.getCurrentLocation(userId);
            }
            
            this.currentGPSLocation = newLocation;
            this.locationHistory.add(newLocation);
            
            progress = Math.min(100.0, (currentSegmentIndex / (double) segments.size()) * 100);
            
            if (progress >= 99.9) {
                isActive = false;
                UserLocation userLoc = userLocations.get(userId);
                if (userLoc != null) {
                    userLoc.setNavigating(false);
                }
            }
        }
        
        private double calculateSegmentProgress(RouteSegment segment) {
            Location currentLoc = new Location(currentGPSLocation.getLatitude(), currentGPSLocation.getLongitude(), "Current");
            double totalDistance = segment.getStartPoint().distanceTo(segment.getEndPoint());
            double traveledDistance = segment.getStartPoint().distanceTo(currentLoc);
            return traveledDistance / totalDistance;
        }
        
        public int getRemainingTime() {
            int totalRemaining = 0;
            for (int i = currentSegmentIndex; i < segments.size(); i++) {
                totalRemaining += segments.get(i).getDuration();
            }
            return totalRemaining;
        }
        
        public double getRemainingDistance() {
            double totalRemaining = 0;
            for (int i = currentSegmentIndex; i < segments.size(); i++) {
                totalRemaining += segments.get(i).getDistance();
            }
            return totalRemaining;
        }
        
        public String getCurrentInstruction() {
            if (currentSegmentIndex < segments.size()) {
                return segments.get(currentSegmentIndex).getInstruction();
            }
            return "You have arrived at your destination";
        }
        
        public RouteSegment getCurrentSegment() {
            if (currentSegmentIndex < segments.size()) {
                return segments.get(currentSegmentIndex);
            }
            return null;
        }
        
        public String getNextVoiceInstruction() {
            String instruction = getCurrentInstruction();
            RouteSegment currentSegment = getCurrentSegment();
            if (currentSegment != null && currentSegment.isCriticalSegment()) {
                instruction = "Important: " + instruction;
            }
            
            if (!spokenInstructions.contains(instruction)) {
                spokenInstructions.add(instruction);
                return instruction;
            }
            return null;
        }
        
        public List<Map<String, Object>> getRoutePath() {
            List<Map<String, Object>> path = new ArrayList<>();
            
            path.add(currentGPSLocation.toMap());
            
            for (int i = currentSegmentIndex; i < segments.size(); i++) {
                RouteSegment segment = segments.get(i);
                path.add(segment.getStartPoint().toMap());
                if (i == segments.size() - 1) {
                    path.add(segment.getEndPoint().toMap());
                }
            }
            
            return path;
        }
        
        public SafetyAnalysis getCurrentSafetyAnalysis() {
            return safetyAnalysisService.analyzeRouteSafety(route, currentGPSLocation);
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("sessionId", sessionId);
            map.put("route", route.toMap());
            map.put("currentSegmentIndex", currentSegmentIndex);
            map.put("totalSegments", segments.size());
            map.put("progress", progress);
            map.put("remainingTime", getRemainingTime());
            map.put("remainingDistance", getRemainingDistance());
            map.put("currentInstruction", getCurrentInstruction());
            map.put("nextVoiceInstruction", getNextVoiceInstruction());
            map.put("isActive", isActive);
            map.put("currentLocation", currentGPSLocation.toMap());
            map.put("speed", currentGPSLocation.getSpeed());
            map.put("routePath", getRoutePath());
            
            RouteSegment currentSegment = getCurrentSegment();
            if (currentSegment != null) {
                map.put("currentSegment", currentSegment.toMap());
            }
            
            map.put("safetyAnalysis", getCurrentSafetyAnalysis().toMap());
            
            return map;
        }
    }
    
    static class SafetyAnalysisService {
        private static final double SAFE_DISTANCE_THRESHOLD = 2.0;
        
        public SafetyAnalysis analyzeRouteSafety(Route route, GPSLocation currentLocation) {
            List<SafetyReport> nearbyReports = getNearbySafetyReports(route, currentLocation);
            int baseSafetyScore = route.getSafetyScore();
            int adjustedScore = adjustSafetyScore(baseSafetyScore, nearbyReports);
            SafetyLevel overallLevel = SafetyLevel.fromScore(adjustedScore);
            
            List<String> safetyRecommendations = generateSafetyRecommendations(overallLevel, nearbyReports, route);
            List<Route> alternativeRoutes = getAlternativeRoutes(route, currentLocation);
            
            return new SafetyAnalysis(
                route.getRouteId(),
                overallLevel,
                adjustedScore,
                baseSafetyScore,
                nearbyReports,
                safetyRecommendations,
                calculateRiskFactors(nearbyReports),
                alternativeRoutes
            );
        }
        
        private List<SafetyReport> getNearbySafetyReports(Route route, GPSLocation currentLocation) {
            Location currentLoc = new Location(currentLocation.getLatitude(), currentLocation.getLongitude(), "Current");
            
            return safetyReports.stream()
                .filter(report -> report.isActive() && !report.isExpired())
                .filter(report -> isReportNearRoute(report, route, currentLoc))
                .sorted((r1, r2) -> Integer.compare(r2.getSeverity(), r1.getSeverity()))
                .collect(Collectors.toList());
        }
        
        private boolean isReportNearRoute(SafetyReport report, Route route, Location currentLocation) {
            double distanceToStart = report.getLocation().distanceTo(route.getStartLocation());
            double distanceToEnd = report.getLocation().distanceTo(route.getEndLocation());
            double distanceToCurrent = report.getLocation().distanceTo(currentLocation);
            
            return distanceToStart <= SAFE_DISTANCE_THRESHOLD || 
                   distanceToEnd <= SAFE_DISTANCE_THRESHOLD ||
                   distanceToCurrent <= SAFE_DISTANCE_THRESHOLD;
        }
        
        private int adjustSafetyScore(int baseScore, List<SafetyReport> nearbyReports) {
            int adjustment = 0;
            
            for (SafetyReport report : nearbyReports) {
                switch (report.getSafetyLevel()) {
                    case VERY_SAFE:
                        adjustment += 10;
                        break;
                    case SAFE:
                        adjustment += 5;
                        break;
                    case MODERATE:
                        adjustment -= 5;
                        break;
                    case UNSAFE:
                        adjustment -= 15;
                        break;
                }
            }
            
            return Math.max(0, Math.min(100, baseScore + adjustment));
        }
        
        private List<String> generateSafetyRecommendations(SafetyLevel safetyLevel, 
                                                         List<SafetyReport> reports, 
                                                         Route route) {
            List<String> recommendations = new ArrayList<>();
            
            switch (safetyLevel) {
                case UNSAFE:
                    recommendations.add("🚨 AVOID THIS ROUTE - Critical safety issues detected");
                    recommendations.add("Use alternative route or delay travel");
                    break;
                case MODERATE:
                    recommendations.add("⚠️ Moderate Risk - Travel with caution");
                    recommendations.add("Avoid nighttime travel on this route");
                    break;
                case SAFE:
                    recommendations.add("🔷 Safe Route - Standard precautions recommended");
                    break;
                case VERY_SAFE:
                    recommendations.add("✅ Very Safe Route - Optimal conditions");
                    break;
            }
            
            if (route.getDistance() > 50) {
                recommendations.add("Long distance route - plan for breaks");
            }
            
            return recommendations;
        }
        
        private List<String> calculateRiskFactors(List<SafetyReport> reports) {
            return reports.stream()
                .map(report -> report.getType() + " - " + report.getSafetyLevel().getDisplayName())
                .distinct()
                .collect(Collectors.toList());
        }
        
        private List<Route> getAlternativeRoutes(Route originalRoute, GPSLocation currentLocation) {
            return predefinedRoutes.values().stream()
                .flatMap(List::stream)
                .filter(route -> !route.getRouteId().equals(originalRoute.getRouteId()))
                .filter(route -> route.getStartLocation().equals(originalRoute.getStartLocation()))
                .filter(route -> route.getEndLocation().equals(originalRoute.getEndLocation()))
                .sorted((r1, r2) -> Integer.compare(r2.getSafetyScore(), r1.getSafetyScore()))
                .limit(2)
                .collect(Collectors.toList());
        }
    }
    
    static class SafetyAnalysis {
        private String routeId;
        private SafetyLevel overallSafetyLevel;
        private int adjustedScore;
        private int baseScore;
        private List<SafetyReport> safetyReports;
        private List<String> recommendations;
        private List<String> riskFactors;
        private List<Route> alternativeRoutes;
        
        public SafetyAnalysis(String routeId, SafetyLevel overallSafetyLevel, int adjustedScore, 
                             int baseScore, List<SafetyReport> safetyReports, 
                             List<String> recommendations, List<String> riskFactors,
                             List<Route> alternativeRoutes) {
            this.routeId = routeId;
            this.overallSafetyLevel = overallSafetyLevel;
            this.adjustedScore = adjustedScore;
            this.baseScore = baseScore;
            this.safetyReports = safetyReports;
            this.recommendations = recommendations;
            this.riskFactors = riskFactors;
            this.alternativeRoutes = alternativeRoutes;
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("routeId", routeId);
            map.put("overallSafetyLevel", overallSafetyLevel.name());
            map.put("safetyEmoji", overallSafetyLevel.getEmoji());
            map.put("safetyDisplayName", overallSafetyLevel.getDisplayName());
            map.put("adjustedScore", adjustedScore);
            map.put("baseScore", baseScore);
            map.put("safetyReports", safetyReports.stream().map(SafetyReport::toMap).collect(Collectors.toList()));
            map.put("recommendations", recommendations);
            map.put("riskFactors", riskFactors);
            map.put("alternativeRoutes", alternativeRoutes.stream().map(Route::toMap).collect(Collectors.toList()));
            map.put("colorCode", getColorCode());
            return map;
        }
        
        public String getColorCode() {
            switch (overallSafetyLevel) {
                case VERY_SAFE: return "#27ae60";
                case SAFE: return "#3498db";
                case MODERATE: return "#f39c12";
                case UNSAFE: return "#e74c3c";
                default: return "#95a5a6";
            }
        }
    }
    
    enum SafetyLevel {
        VERY_SAFE("✅", "Very Safe", 80, 100),
        SAFE("🔷", "Safe", 60, 79),
        MODERATE("⚠️", "Moderate", 40, 59),
        UNSAFE("🚨", "Unsafe", 0, 39);
        
        private final String emoji;
        private final String displayName;
        private final int minScore;
        private final int maxScore;
        
        SafetyLevel(String emoji, String displayName, int minScore, int maxScore) {
            this.emoji = emoji;
            this.displayName = displayName;
            this.minScore = minScore;
            this.maxScore = maxScore;
        }
        
        public String getEmoji() { return emoji; }
        public String getDisplayName() { return displayName; }
        
        public static SafetyLevel fromScore(int score) {
            for (SafetyLevel level : values()) {
                if (score >= level.minScore && score <= level.maxScore) {
                    return level;
                }
            }
            return MODERATE;
        }
    }
    
    static class SafetyReport {
        private String reportId;
        private String type;
        private String description;
        private Location location;
        private int severity;
        private LocalDateTime reportedAt;
        private LocalDateTime expiresAt;
        
        public SafetyReport(String type, String description, Location location, int severity) {
            this.reportId = UUID.randomUUID().toString();
            this.type = type;
            this.description = description;
            this.location = location;
            this.severity = Math.max(0, Math.min(100, severity));
            this.reportedAt = LocalDateTime.now();
            this.expiresAt = reportedAt.plusHours(24);
        }
        
        public String getReportId() { return reportId; }
        public String getType() { return type; }
        public String getDescription() { return description; }
        public Location getLocation() { return location; }
        public int getSeverity() { return severity; }
        public LocalDateTime getReportedAt() { return reportedAt; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
        
        public boolean isActive() { return true; }
        public boolean isExpired() { return LocalDateTime.now().isAfter(expiresAt); }
        
        public SafetyLevel getSafetyLevel() {
            if (severity >= 80) return SafetyLevel.VERY_SAFE;
            if (severity >= 60) return SafetyLevel.SAFE;
            if (severity >= 40) return SafetyLevel.MODERATE;
            return SafetyLevel.UNSAFE;
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("reportId", reportId);
            map.put("type", type);
            map.put("description", description);
            map.put("location", location.toMap());
            map.put("severity", severity);
            map.put("safetyLevel", getSafetyLevel().name());
            map.put("emoji", getSafetyLevel().getEmoji());
            map.put("reportedAt", reportedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            map.put("expiresAt", expiresAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            map.put("isActive", isActive());
            map.put("isExpired", isExpired());
            return map;
        }
    }
    
    static class Location {
        private double latitude;
        private double longitude;
        private String address;
        
        public Location(double latitude, double longitude, String address) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.address = address;
        }
        
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
        public String getAddress() { return address; }
        
        public double distanceTo(Location other) {
            double dLat = Math.toRadians(other.latitude - this.latitude);
            double dLng = Math.toRadians(other.longitude - this.longitude);
            double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                      Math.cos(Math.toRadians(this.latitude)) * Math.cos(Math.toRadians(other.latitude)) *
                      Math.sin(dLng/2) * Math.sin(dLng/2);
            double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
            return 6371 * c;
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("lat", latitude);
            map.put("lng", longitude);
            map.put("address", address);
            return map;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Location location = (Location) obj;
            return Double.compare(location.latitude, latitude) == 0 &&
                   Double.compare(location.longitude, longitude) == 0 &&
                   Objects.equals(address, location.address);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(latitude, longitude, address);
        }
    }
    
    static class Route {
        private String routeId;
        private String name;
        private Location startLocation;
        private Location endLocation;
        private int duration;
        private double distance;
        private int safetyScore;
        
        public Route(String name, Location startLocation, Location endLocation, int duration, double distance) {
            this.routeId = UUID.randomUUID().toString();
            this.name = name;
            this.startLocation = startLocation;
            this.endLocation = endLocation;
            this.duration = duration;
            this.distance = distance;
            this.safetyScore = calculateSafetyScore();
        }
        
        public String getRouteId() { return routeId; }
        public String getName() { return name; }
        public Location getStartLocation() { return startLocation; }
        public Location getEndLocation() { return endLocation; }
        public int getDuration() { return duration; }
        public double getDistance() { return distance; }
        public int getSafetyScore() { return safetyScore; }
        
        private int calculateSafetyScore() {
            Random random = new Random(routeId.hashCode());
            int baseScore = 60 + random.nextInt(35);
            
            if (distance > 50) baseScore -= 10;
            if (duration > 120) baseScore -= 5;
            
            return Math.max(0, Math.min(100, baseScore));
        }
        
        public SafetyLevel getSafetyLevel() {
            return SafetyLevel.fromScore(safetyScore);
        }
        
        public List<String> getSafetyFactors() {
            List<String> factors = new ArrayList<>();
            if (safetyScore >= 80) factors.add("Well-maintained roads");
            if (safetyScore >= 70) factors.add("Good lighting");
            if (safetyScore >= 60) factors.add("Regular patrols");
            if (distance > 50) factors.add("Long distance");
            if (duration > 120) factors.add("Extended travel time");
            return factors;
        }
        
        public String getFormattedDuration() {
            if (duration < 60) return duration + " min";
            int hours = duration / 60;
            int minutes = duration % 60;
            return hours + "h " + minutes + "min";
        }
        
        public String getFormattedDistance() {
            if (distance < 1) return Math.round(distance * 1000) + " m";
            return String.format("%.1f km", distance);
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("routeId", routeId);
            map.put("name", name);
            map.put("startLocation", startLocation.toMap());
            map.put("endLocation", endLocation.toMap());
            map.put("duration", duration);
            map.put("formattedDuration", getFormattedDuration());
            map.put("distance", distance);
            map.put("formattedDistance", getFormattedDistance());
            map.put("safetyScore", safetyScore);
            map.put("safetyLevel", getSafetyLevel().name());
            map.put("safetyEmoji", getSafetyLevel().getEmoji());
            map.put("safetyFactors", getSafetyFactors());
            return map;
        }
    }
    
    static class RouteSegment {
        private String segmentId;
        private Location startPoint;
        private Location endPoint;
        private double distance;
        private int duration;
        private String instruction;
        private boolean criticalSegment;
        
        public RouteSegment(Location startPoint, Location endPoint, double distance, int duration, String instruction) {
            this.segmentId = UUID.randomUUID().toString();
            this.startPoint = startPoint;
            this.endPoint = endPoint;
            this.distance = distance;
            this.duration = duration;
            this.instruction = instruction;
            this.criticalSegment = instruction.toLowerCase().contains("turn") || 
                                  instruction.toLowerCase().contains("exit") ||
                                  instruction.toLowerCase().contains("merge");
        }
        
        public String getSegmentId() { return segmentId; }
        public Location getStartPoint() { return startPoint; }
        public Location getEndPoint() { return endPoint; }
        public double getDistance() { return distance; }
        public int getDuration() { return duration; }
        public String getInstruction() { return instruction; }
        public boolean isCriticalSegment() { return criticalSegment; }
        
        public static List<RouteSegment> createSegmentsFromRoute(Route route) {
            List<RouteSegment> segments = new ArrayList<>();
            Random random = new Random(route.getRouteId().hashCode());
            
            double totalDistance = route.getDistance();
            int totalDuration = route.getDuration();
            int segmentCount = Math.max(3, random.nextInt(6) + 3);
            
            double segmentDistance = totalDistance / segmentCount;
            int segmentDuration = totalDuration / segmentCount;
            
            Location currentStart = route.getStartLocation();
            
            for (int i = 0; i < segmentCount; i++) {
                double progress = (i + 1) / (double) segmentCount;
                double segLat = route.getStartLocation().getLatitude() + 
                               (route.getEndLocation().getLatitude() - route.getStartLocation().getLatitude()) * progress;
                double segLng = route.getStartLocation().getLongitude() + 
                               (route.getEndLocation().getLongitude() - route.getStartLocation().getLongitude()) * progress;
                
                Location segmentEnd = new Location(segLat, segLng, "Route Point " + (i + 1));
                
                String instruction;
                if (i == segmentCount - 1) {
                    instruction = "Arrive at destination: " + route.getEndLocation().getAddress();
                } else if (i == 0) {
                    instruction = "Start from " + route.getStartLocation().getAddress();
                } else {
                    String[] instructions = {
                        "Continue straight for " + String.format("%.1f", segmentDistance) + " km",
                        "Follow the road for " + segmentDuration + " minutes",
                        "Keep left and continue",
                        "Stay on this route"
                    };
                    instruction = instructions[random.nextInt(instructions.length)];
                }
                
                segments.add(new RouteSegment(currentStart, segmentEnd, segmentDistance, segmentDuration, instruction));
                currentStart = segmentEnd;
            }
            
            return segments;
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("segmentId", segmentId);
            map.put("startPoint", startPoint.toMap());
            map.put("endPoint", endPoint.toMap());
            map.put("distance", distance);
            map.put("duration", duration);
            map.put("instruction", instruction);
            map.put("criticalSegment", criticalSegment);
            return map;
        }
    }
    
    private static void initializeErodeLocations() {
        erodeLocations.put("erode_center", new Location(11.3410, 77.7172, "Erode City Center"));
        erodeLocations.put("erode_railway", new Location(11.3460, 77.7225, "Erode Railway Station"));
        erodeLocations.put("erode_bus", new Location(11.3385, 77.7280, "Erode Bus Stand"));
        erodeLocations.put("perundurai", new Location(11.2756, 77.5878, "Perundurai"));
        erodeLocations.put("sathy", new Location(11.5053, 77.2384, "Sathyamangalam"));
        erodeLocations.put("gobi", new Location(11.4550, 77.4392, "Gobichettipalayam"));
        erodeLocations.put("bhavani", new Location(11.4471, 77.6846, "Bhavani"));
        erodeLocations.put("turmeric_market", new Location(11.3450, 77.7200, "Turmeric Market"));
        erodeLocations.put("textile_market", new Location(11.3430, 77.7150, "Textile Market"));
        erodeLocations.put("medical_college", new Location(11.2800, 77.6000, "Government Medical College"));
        erodeLocations.put("cbe_road", new Location(11.3300, 77.7000, "Coimbatore Road"));
        erodeLocations.put("salem_road", new Location(11.3500, 77.7300, "Salem Road"));
        erodeLocations.put("trichy_road", new Location(11.3200, 77.7400, "Trichy Road"));
    }
    
    private static void initializeErodeRoutes() {
        Random random = new Random();
        
        addRoute("erode_center", "erode_railway", 10, 3.0, random);
        addRoute("erode_center", "erode_bus", 12, 4.0, random);
        addRoute("erode_railway", "erode_bus", 8, 2.0, random);
        addRoute("erode_center", "turmeric_market", 5, 1.0, random);
        addRoute("erode_center", "textile_market", 6, 2.0, random);
        addRoute("erode_center", "perundurai", 30, 20.0, random);
        addRoute("erode_center", "sathy", 90, 65.0, random);
        addRoute("erode_center", "gobi", 55, 40.0, random);
        addRoute("erode_center", "bhavani", 25, 15.0, random);
    }
    
    private static void addRoute(String from, String to, int duration, double distance, Random random) {
        Location start = erodeLocations.get(from);
        Location end = erodeLocations.get(to);
        
        if (start != null && end != null) {
            Route route = new Route("Route " + from + "-" + to, start, end, duration, distance);
            
            String routeKey = from + "-" + to;
            predefinedRoutes.computeIfAbsent(routeKey, k -> new ArrayList<>()).add(route);
            
            String reverseKey = to + "-" + from;
            Route reverseRoute = new Route("Route " + to + "-" + from, end, start, duration, distance);
            predefinedRoutes.computeIfAbsent(reverseKey, k -> new ArrayList<>()).add(reverseRoute);
        }
    }
    
    private static void initializeSampleSafetyReports() {
        safetyReports.add(new SafetyReport("CONSTRUCTION", 
            "Road widening work near Erode Bus Stand", 
            erodeLocations.get("erode_bus"), 60));
        
        safetyReports.add(new SafetyReport("TRAFFIC", 
            "Heavy traffic on Coimbatore Road during peak hours", 
            erodeLocations.get("cbe_road"), 50));
        
        safetyReports.add(new SafetyReport("ROAD_CONDITION", 
            "Poor road condition near Turmeric Market", 
            erodeLocations.get("turmeric_market"), 70));
        
        safetyReports.add(new SafetyReport("SAFE_AREA", 
            "Well-lit and patrolled area near Medical College", 
            erodeLocations.get("medical_college"), 20));
    }
    
    static class LocationsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                List<Map<String, Object>> locationMaps = new ArrayList<>();
                erodeLocations.forEach((key, location) -> {
                    Map<String, Object> locMap = location.toMap();
                    locMap.put("key", key);
                    locationMaps.add(locMap);
                });
                sendJsonResponse(exchange, locationMaps);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }
    
    static class RoutesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
                String from = params.get("from");
                String to = params.get("to");
                
                List<Route> matchingRoutes = new ArrayList<>();
                
                if (from != null && to != null) {
                    String routeKey = from + "-" + to;
                    matchingRoutes = predefinedRoutes.getOrDefault(routeKey, new ArrayList<>());
                    
                    if (matchingRoutes.isEmpty()) {
                        matchingRoutes = findConnectingRoutes(from, to);
                    }
                }
                
                List<Map<String, Object>> routeMaps = matchingRoutes.stream()
                    .map(Route::toMap)
                    .collect(Collectors.toList());
                
                sendJsonResponse(exchange, routeMaps);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
        
        private List<Route> findConnectingRoutes(String from, String to) {
            List<Route> connectingRoutes = new ArrayList<>();
            if (erodeLocations.containsKey(from) && erodeLocations.containsKey(to)) {
                Location start = erodeLocations.get(from);
                Location end = erodeLocations.get(to);
                
                double distance = start.distanceTo(end);
                int approxDuration = (int) (distance * 1.5);
                
                Route connectingRoute = new Route("Connecting Route " + from + "-" + to, 
                    start, end, approxDuration, distance);
                connectingRoutes.add(connectingRoute);
            }
            return connectingRoutes;
        }
    }
    
    static class NavigationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            
            try {
                if ("POST".equals(method) && path.endsWith("/start")) {
                    handleNavigationStart(exchange);
                } else if ("GET".equals(method) && path.endsWith("/progress")) {
                    handleNavigationProgress(exchange);
                } else if ("POST".equals(method) && path.endsWith("/stop")) {
                    handleNavigationStop(exchange);
                } else if ("GET".equals(method) && path.endsWith("/segments")) {
                    handleNavigationSegments(exchange);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            } catch (Exception e) {
                sendError(exchange, "Navigation error: " + e.getMessage());
            }
        }
        
        private void handleNavigationStart(HttpExchange exchange) throws IOException {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> navData = parseJson(requestBody);
            
            String routeId = (String) navData.get("routeId");
            String userId = (String) navData.getOrDefault("userId", "user_" + System.currentTimeMillis());
            
            Route selectedRoute = findRouteById(routeId);
            
            if (selectedRoute == null) {
                sendError(exchange, "Route not found");
                return;
            }
            
            String sessionId = UUID.randomUUID().toString();
            NavigationSession session = new NavigationSession(sessionId, userId, selectedRoute);
            navigationSessions.put(sessionId, session);
            
            Map<String, Object> response = new HashMap<>();
            response.put("sessionId", sessionId);
            response.put("status", "navigation_started");
            response.put("message", "Navigation started to: " + selectedRoute.getEndLocation().getAddress());
            response.put("session", session.toMap());
            
            sendJsonResponse(exchange, response);
        }
        
        private void handleNavigationProgress(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
            String sessionId = params.get("sessionId");
            
            NavigationSession session = navigationSessions.get(sessionId);
            if (session == null) {
                sendError(exchange, "Navigation session not found");
                return;
            }
            
            session.updateGPSLocation();
            sendJsonResponse(exchange, session.toMap());
        }
        
        private void handleNavigationStop(HttpExchange exchange) throws IOException {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> data = parseJson(requestBody);
            String sessionId = (String) data.get("sessionId");
            
            NavigationSession session = navigationSessions.remove(sessionId);
            if (session != null) {
                routeSegments.remove(sessionId);
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Navigation stopped");
            sendJsonResponse(exchange, response);
        }
        
        private void handleNavigationSegments(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
            String sessionId = params.get("sessionId");
            
            List<RouteSegment> segments = routeSegments.get(sessionId);
            if (segments == null) {
                sendError(exchange, "No segments found for session");
                return;
            }
            
            List<Map<String, Object>> segmentMaps = segments.stream()
                .map(RouteSegment::toMap)
                .collect(Collectors.toList());
            
            sendJsonResponse(exchange, segmentMaps);
        }
        
        private Route findRouteById(String routeId) {
            for (List<Route> routes : predefinedRoutes.values()) {
                for (Route route : routes) {
                    if (route.getRouteId().equals(routeId)) {
                        return route;
                    }
                }
            }
            return null;
        }
    }
    
    static class SafetyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            
            try {
                if ("GET".equals(method) && path.endsWith("/analysis")) {
                    handleSafetyAnalysis(exchange);
                } else if ("GET".equals(method) && path.endsWith("/reports")) {
                    handleSafetyReports(exchange);
                } else if ("POST".equals(method) && path.endsWith("/reports")) {
                    handleSubmitSafetyReport(exchange);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            } catch (Exception e) {
                sendError(exchange, "Safety service error: " + e.getMessage());
            }
        }
        
        private void handleSafetyAnalysis(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
            String routeId = params.get("routeId");
            String userId = params.getOrDefault("userId", "default_user");
            
            Route route = findRouteById(routeId);
            if (route == null) {
                sendError(exchange, "Route not found");
                return;
            }
            
            GPSLocation currentLocation = gpsService.getCurrentLocation(userId);
            SafetyAnalysis analysis = safetyAnalysisService.analyzeRouteSafety(route, currentLocation);
            
            sendJsonResponse(exchange, analysis.toMap());
        }
        
        private void handleSafetyReports(HttpExchange exchange) throws IOException {
            List<Map<String, Object>> activeReports = safetyReports.stream()
                .filter(report -> report.isActive() && !report.isExpired())
                .map(SafetyReport::toMap)
                .collect(Collectors.toList());
            
            sendJsonResponse(exchange, activeReports);
        }
        
        private void handleSubmitSafetyReport(HttpExchange exchange) throws IOException {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> reportData = parseJson(requestBody);
            
            String type = (String) reportData.get("type");
            String description = (String) reportData.get("description");
            int severity = Integer.parseInt(reportData.get("severity").toString());
            
            Location location;
            if (reportData.containsKey("locationKey")) {
                String locationKey = (String) reportData.get("locationKey");
                location = erodeLocations.get(locationKey);
            } else {
                double lat = Double.parseDouble(reportData.get("lat").toString());
                double lng = Double.parseDouble(reportData.get("lng").toString());
                location = new Location(lat, lng, "User Reported Location");
            }
            
            if (location == null) {
                sendError(exchange, "Invalid location");
                return;
            }
            
            SafetyReport newReport = new SafetyReport(type, description, location, severity);
            safetyReports.add(newReport);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("reportId", newReport.getReportId());
            response.put("message", "Safety report submitted successfully");
            
            sendJsonResponse(exchange, response);
        }
        
        private Route findRouteById(String routeId) {
            for (List<Route> routes : predefinedRoutes.values()) {
                for (Route route : routes) {
                    if (route.getRouteId().equals(routeId)) {
                        return route;
                    }
                }
            }
            return null;
        }
    }
    
    static class GPSHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
                String userId = params.getOrDefault("userId", "default_user");
                
                GPSLocation location = gpsService.getCurrentLocation(userId);
                sendJsonResponse(exchange, location.toMap());
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }
    
    static class CurrentLocationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
                String userId = params.getOrDefault("userId", "default_user");
                
                UserLocation userLoc = userLocations.get(userId);
                if (userLoc == null) {
                    GPSLocation gpsLoc = gpsService.getCurrentLocation(userId);
                    userLoc = new UserLocation(userId);
                    userLoc.updateLocation(gpsLoc);
                    userLocations.put(userId, userLoc);
                }
                
                Map<String, Object> response = new HashMap<>();
                response.put("userId", userId);
                response.put("location", userLoc.getCurrentLocation().toMap());
                response.put("lastUpdate", userLoc.getLastUpdate().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                response.put("isNavigating", userLoc.isNavigating());
                
                sendJsonResponse(exchange, response);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }
    
    static class NetworkInfoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                Map<String, Object> response = new HashMap<>();
                try {
                    response.put("localIP", InetAddress.getLocalHost().getHostAddress());
                    response.put("hostname", InetAddress.getLocalHost().getHostName());
                } catch (Exception e) {
                    response.put("localIP", "unknown");
                    response.put("hostname", "unknown");
                }
                sendJsonResponse(exchange, response);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String htmlContent = getHTMLContent();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, htmlContent.getBytes(StandardCharsets.UTF_8).length);
            OutputStream os = exchange.getResponseBody();
            os.write(htmlContent.getBytes(StandardCharsets.UTF_8));
            os.close();
        }
        
        private String getHTMLContent() {
            return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes">
    <meta name="apple-mobile-web-app-capable" content="yes">
    <meta name="mobile-web-app-capable" content="yes">
    <title>SafePath - Erode Navigation</title>
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.7.1/dist/leaflet.css" />
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { 
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
            background: #f0f2f5;
            line-height: 1.6;
            color: #333;
            -webkit-tap-highlight-color: transparent;
        }
        
        .container { 
            max-width: 1400px;
            margin: 0 auto; 
            padding: clamp(8px, 3vw, 20px);
            min-height: 100vh;
        }
        
        .header { 
            background: linear-gradient(135deg, #27ae60, #2ecc71); 
            color: white; 
            padding: clamp(15px, 4vw, 30px);
            border-radius: 12px; 
            text-align: center; 
            margin-bottom: clamp(12px, 3vw, 20px);
            box-shadow: 0 4px 15px rgba(0,0,0,0.1);
        }
        
        .header h1 {
            font-size: clamp(1.4rem, 5vw, 2.2rem);
            margin-bottom: clamp(8px, 2vw, 12px);
            font-weight: 700;
        }
        
        .header p {
            font-size: clamp(0.9rem, 3vw, 1.1rem);
            opacity: 0.9;
        }
        
        .tabs { 
            display: flex; 
            background: white; 
            border-radius: 12px; 
            margin-bottom: clamp(12px, 3vw, 20px); 
            overflow: hidden;
            box-shadow: 0 2px 10px rgba(0,0,0,0.1);
            flex-wrap: wrap;
        }
        
        .tab { 
            flex: 1; 
            padding: clamp(12px, 3vw, 18px) clamp(8px, 2vw, 15px); 
            text-align: center; 
            cursor: pointer; 
            border: none; 
            background: none; 
            transition: all 0.3s ease;
            font-size: clamp(0.8rem, 2.5vw, 1rem);
            font-weight: 600;
            min-width: 120px;
            min-height: 50px;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        
        .tab.active { 
            background: #27ae60; 
            color: white;
            transform: translateY(-2px);
        }
        
        .content { 
            background: white; 
            padding: clamp(15px, 4vw, 25px); 
            border-radius: 12px; 
            min-height: 60vh;
            box-shadow: 0 2px 10px rgba(0,0,0,0.1);
        }
        
        #navigationMap { 
            height: clamp(250px, 50vh, 500px); 
            width: 100%; 
            border-radius: 10px; 
            margin-bottom: clamp(12px, 3vw, 20px);
            border: 2px solid #e9ecef;
            background: #f8f9fa;
        }
        
        .navigation-info {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
            gap: clamp(8px, 2vw, 15px);
            margin-bottom: clamp(12px, 3vw, 20px);
        }
        
        .info-card {
            background: #f8f9fa;
            padding: clamp(10px, 2vw, 15px);
            border-radius: 10px;
            text-align: center;
            border-left: 4px solid #27ae60;
            transition: transform 0.2s ease;
        }
        
        .info-card:hover {
            transform: translateY(-2px);
        }
        
        .info-card h3 {
            margin: 0 0 clamp(6px, 1.5vw, 10px) 0;
            font-size: clamp(0.7rem, 2vw, 0.9rem);
            color: #666;
            font-weight: 600;
        }
        
        .info-card .value {
            font-size: clamp(1.1rem, 4vw, 1.5rem);
            font-weight: bold;
            color: #27ae60;
        }
        
        .voice-controls {
            background: #e8f4fd;
            padding: clamp(12px, 3vw, 18px);
            border-radius: 12px;
            margin: clamp(10px, 2.5vw, 15px) 0;
            display: flex;
            flex-wrap: wrap;
            gap: clamp(8px, 2vw, 12px);
            align-items: center;
        }
        
        .voice-btn, .btn {
            background: #27ae60;
            color: white;
            border: none;
            padding: clamp(10px, 2.5vw, 14px) clamp(15px, 3vw, 20px);
            border-radius: 8px;
            cursor: pointer;
            transition: all 0.3s ease;
            font-size: clamp(0.85rem, 2.5vw, 1rem);
            font-weight: 600;
            min-height: 44px;
            min-width: 44px;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            text-decoration: none;
            flex-shrink: 0;
        }
        
        .voice-btn:hover, .btn:hover {
            background: #219653;
            transform: translateY(-2px);
            box-shadow: 0 4px 12px rgba(39, 174, 96, 0.3);
        }
        
        .btn.stop { background: #e74c3c; }
        .btn.stop:hover { background: #c0392b; }
        .btn.secondary { background: #95a5a6; }
        .btn.secondary:hover { background: #7f8c8d; }
        
        .route-card {
            border: 1px solid #e9ecef;
            padding: clamp(12px, 3vw, 18px);
            margin: clamp(8px, 2vw, 12px) 0;
            border-radius: 10px;
            background: #fafafa;
            transition: all 0.3s ease;
            box-shadow: 0 2px 8px rgba(0,0,0,0.05);
        }
        
        .route-card:hover {
            transform: translateY(-2px);
            box-shadow: 0 4px 15px rgba(0,0,0,0.1);
        }
        
        .location-selector { 
            background: #f8f9fa; 
            padding: clamp(15px, 4vw, 25px); 
            border-radius: 12px; 
            margin-bottom: clamp(12px, 3vw, 20px);
            border: 2px solid #e9ecef;
        }
        
        .form-group {
            margin-bottom: clamp(12px, 3vw, 18px);
        }
        
        .form-group label {
            display: block;
            margin-bottom: 6px;
            font-weight: 600;
            color: #555;
            font-size: clamp(0.9rem, 2.5vw, 1rem);
        }
        
        select, input {
            width: 100%;
            padding: clamp(10px, 2.5vw, 14px);
            border: 2px solid #e9ecef;
            border-radius: 8px;
            font-size: clamp(0.9rem, 2.5vw, 1rem);
            transition: border-color 0.3s ease;
        }
        
        select:focus, input:focus {
            outline: none;
            border-color: #27ae60;
            box-shadow: 0 0 0 3px rgba(39, 174, 96, 0.1);
        }
        
        .instruction-panel {
            background: linear-gradient(135deg, #27ae60, #2ecc71);
            color: white;
            padding: clamp(12px, 3vw, 18px);
            margin: clamp(8px, 2vw, 12px) 0;
            border-radius: 10px;
            box-shadow: 0 4px 15px rgba(39, 174, 96, 0.3);
        }
        
        .instruction-panel h4 {
            margin: 0 0 clamp(8px, 2vw, 12px) 0;
            font-size: clamp(1rem, 3vw, 1.2rem);
            opacity: 0.9;
        }
        
        .instruction-panel p {
            font-size: clamp(0.95rem, 2.5vw, 1.1rem);
            font-weight: 600;
        }
        
        .safety-alert {
            background: #fff3cd;
            border-left: 4px solid #f39c12;
            padding: clamp(10px, 2.5vw, 14px);
            margin: clamp(6px, 1.5vw, 10px) 0;
            border-radius: 6px;
            font-size: clamp(0.85rem, 2.5vw, 0.95rem);
        }
        
        .route-options { 
            display: grid; 
            grid-template-columns: repeat(auto-fit, minmax(min(100%, 320px), 1fr)); 
            gap: clamp(10px, 2.5vw, 15px); 
            margin-top: clamp(12px, 3vw, 20px); 
        }
        
        .safety-badge {
            display: inline-flex;
            align-items: center;
            padding: 6px 12px;
            border-radius: 20px;
            font-size: 0.8rem;
            font-weight: 600;
            margin: 2px;
        }
        
        .segment-card {
            border-left: 4px solid #27ae60;
            padding: 12px;
            margin: 8px 0;
            background: #f8f9fa;
            border-radius: 0 8px 8px 0;
        }
        
        @media (max-width: 768px) {
            .tabs {
                border-radius: 10px;
            }
            
            .tab {
                min-width: 33.333%;
                font-size: 0.8rem;
            }
            
            .voice-controls {
                flex-direction: column;
                align-items: stretch;
            }
            
            .voice-btn, .btn {
                width: 100%;
                justify-content: center;
            }
            
            .navigation-info {
                grid-template-columns: repeat(2, 1fr);
            }
        }
        
        @media (max-width: 480px) {
            .container {
                padding: 6px;
            }
            
            .tab {
                min-width: 50%;
                font-size: 0.75rem;
                padding: 12px 6px;
            }
            
            .navigation-info {
                grid-template-columns: 1fr;
            }
            
            #navigationMap {
                height: 200px;
            }
        }
        
        @media (min-width: 1024px) {
            .container {
                padding: 25px;
            }
            
            .content {
                padding: 30px;
            }
            
            .route-options {
                grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));
            }
            
            .navigation-info {
                grid-template-columns: repeat(4, 1fr);
            }
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>SafePath - Erode Navigation</h1>
            <p>Intelligent Route Planning with Safety Analysis</p>
            <p style="font-size: 0.9rem; margin-top: 8px; opacity: 0.8;">📍 Safe Navigation for Erode District</p>
        </div>
        
        <div class="tabs">
            <button class="tab active" onclick="showTab('plan', this)">🗺️ Plan Journey</button>
            <button class="tab" onclick="showTab('navigation', this)">🚗 Live Navigation</button>
            <button class="tab" onclick="showTab('safety', this)">🛡️ Safety Analysis</button>
            <button class="tab" onclick="showTab('help', this)">❓ Help</button>
        </div>
        
        <div class="content">
            <!-- Plan Journey Tab -->
            <div id="plan">
                <h2>Plan Your Safe Journey</h2>
                <div class="location-selector">
                    <div class="form-group">
                        <label for="fromLocation">📍 Current Location:</label>
                        <select id="fromLocation">
                            <option value="">Select your current location</option>
                        </select>
                    </div>
                    <div class="form-group">
                        <label for="toLocation">🎯 Destination:</label>
                        <select id="toLocation">
                            <option value="">Select your destination</option>
                        </select>
                    </div>
                    <button class="btn" onclick="findRoutes()">
                        🔍 Find Safe Routes
                    </button>
                </div>
                <div id="routesList">
                    <p>Select your current location and destination to see safe routes in Erode district.</p>
                </div>
            </div>
            
            <!-- Live Navigation Tab -->
            <div id="navigation" style="display: none;">
                <h2>Live Navigation</h2>
                <div id="navigationMap">
                    <div style="height: 100%; display: flex; align-items: center; justify-content: center; background: #f8f9fa; color: #666; border-radius: 10px;">
                        <div style="text-align: center; padding: 20px;">
                            <h3>🌍 Erode Navigation Map</h3>
                            <p>Map will load when navigation starts</p>
                            <button class="btn" onclick="initMap()" style="margin-top: 15px;">
                                Load Erode Map
                            </button>
                        </div>
                    </div>
                </div>
                
                <div class="voice-controls">
                    <button class="voice-btn" onclick="toggleVoiceAssistant()">
                        <span id="voiceIcon">🔊</span>
                        <span id="voiceStatus">Voice Assistant: ON</span>
                    </button>
                    <button class="btn secondary" onclick="repeatInstruction()">
                        🔄 Repeat Instruction
                    </button>
                    <button class="btn stop" onclick="stopNavigation()">
                        🛑 Stop Navigation
                    </button>
                </div>
                
                <div class="navigation-info">
                    <div class="info-card">
                        <h3>DISTANCE TO GO</h3>
                        <div class="value" id="distanceRemaining">-- km</div>
                    </div>
                    <div class="info-card">
                        <h3>TIME TO DESTINATION</h3>
                        <div class="value" id="timeRemaining">-- min</div>
                    </div>
                    <div class="info-card">
                        <h3>CURRENT SPEED</h3>
                        <div class="value" id="currentSpeed">-- km/h</div>
                    </div>
                    <div class="info-card">
                        <h3>SAFETY LEVEL</h3>
                        <div class="value" id="safetyLevel">--</div>
                    </div>
                </div>
                
                <div id="navInfo">
                    <div class="instruction-panel">
                        <h4>Next Instruction</h4>
                        <p id="currentInstruction">Start navigation to see instructions</p>
                    </div>
                    <div id="safetyAlerts"></div>
                </div>
            </div>
            
            <!-- Safety Analysis Tab -->
            <div id="safety" style="display: none;">
                <h2>Route Safety Analysis</h2>
                <div class="location-selector">
                    <div class="form-group">
                        <label for="safetyRouteSelect">Select Route for Safety Analysis:</label>
                        <select id="safetyRouteSelect">
                            <option value="">Select a route to analyze</option>
                        </select>
                    </div>
                    <button class="btn" onclick="analyzeRouteSafety()">
                        🛡️ Analyze Safety
                    </button>
                </div>
                <div id="safetyAnalysisResult">
                    <p>Select a route to see detailed safety analysis and recommendations.</p>
                </div>
            </div>
            
            <!-- Help Tab -->
            <div id="help" style="display: none;">
                <h2>Help & Instructions</h2>
                <div class="route-card">
                    <h3>📍 Erode Coverage</h3>
                    <p>• Erode City Center & Landmarks</p>
                    <p>• Railway Station, Bus Stand, Markets</p>
                    <p>• Perundurai, Bhavani, Gobichettipalayam</p>
                    <p>• Sathyamangalam areas</p>
                </div>
                <div class="route-card">
                    <h3>🛡️ Safety Features</h3>
                    <p>• Real-time safety analysis</p>
                    <p>• Route safety scoring</p>
                    <p>• Safety recommendations</p>
                    <p>• Alternative safe routes</p>
                </div>
                <div class="route-card">
                    <h3>🌐 Network Access</h3>
                    <p>To access from other devices:</p>
                    <p><strong>Local:</strong> http://localhost:8080</p>
                    <p><strong>Network:</strong> http://<span id="localIP">YOUR_IP</span>:8080</p>
                </div>
            </div>
        </div>
    </div>

    <script src="https://unpkg.com/leaflet@1.7.1/dist/leaflet.js"></script>
    <script>
        let currentNavigationSession = null;
        let navigationInterval = null;
        let allLocations = [];
        let allRoutes = [];
        let map = null;
        let routeLayer = null;
        let markerLayer = null;
        let voiceAssistantEnabled = true;
        let speechSynthesis = window.speechSynthesis;

        function initApp() {
            loadLocations();
            loadGPS();
            detectDeviceType();
            initMobileFeatures();
            updateLocalIP();
        }

        function detectDeviceType() {
            const isMobile = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
            const isTablet = /iPad|Android/i.test(navigator.userAgent) && !isMobile;
            
            if (isMobile) {
                document.body.classList.add('mobile-device');
                console.log('📱 Mobile device detected');
            } else if (isTablet) {
                document.body.classList.add('tablet-device');
                console.log('📟 Tablet device detected');
            } else {
                document.body.classList.add('desktop-device');
                console.log('💻 Desktop device detected');
            }
        }

        function initMobileFeatures() {
            document.addEventListener('touchstart', function() {}, {passive: true});
            
            window.addEventListener('orientationchange', function() {
                setTimeout(function() {
                    if (map) {
                        map.invalidateSize();
                    }
                }, 300);
            });
        }

        function updateLocalIP() {
            fetch('/api/network-info')
                .then(response => response.json())
                .then(data => {
                    if (data.localIP && data.localIP !== 'unknown') {
                        document.getElementById('localIP').textContent = data.localIP;
                    }
                })
                .catch(() => {
                    document.getElementById('localIP').textContent = 'YOUR_LOCAL_IP';
                });
        }

        function initMap() {
            try {
                console.log('Initializing Erode navigation map...');
                const mapContainer = document.getElementById('navigationMap');
                if (!mapContainer) {
                    throw new Error('Map container not found');
                }
                
                mapContainer.innerHTML = '';
                map = L.map('navigationMap').setView([11.3410, 77.7172], 12);
                
                L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
                    maxZoom: 18,
                    minZoom: 10
                }).addTo(map);
                
                routeLayer = L.layerGroup().addTo(map);
                markerLayer = L.layerGroup().addTo(map);
                
                L.control.scale({ imperial: false }).addTo(map);
                
                if (document.body.classList.contains('mobile-device')) {
                    map.touchZoom.enable();
                    map.scrollWheelZoom.disable();
                } else {
                    map.scrollWheelZoom.enable();
                }
                
                console.log('Erode map initialized successfully');
                
            } catch (error) {
                console.error('Map initialization failed:', error);
                document.getElementById('navigationMap').innerHTML = `
                    <div style="height: 100%; display: flex; align-items: center; justify-content: center; background: #f0f0f0; color: #666; border-radius: 10px;">
                        <div style="text-align: center;">
                            <h3>🌍 Map Loading Failed</h3>
                            <p>Unable to load navigation map.</p>
                            <button class="btn" onclick="initMap()" style="margin-top: 10px;">Retry Map Loading</button>
                        </div>
                    </div>
                `;
            }
        }

        function showTab(tabName, element) {
            // Remove active class from all tabs
            document.querySelectorAll('.tab').forEach(tab => tab.classList.remove('active'));
            
            // Add active class to clicked tab
            if (element) {
                element.classList.add('active');
            }
            
            // Hide all content divs
            document.querySelectorAll('.content > div').forEach(div => div.style.display = 'none');
            
            // Show selected tab content
            const selectedTab = document.getElementById(tabName);
            if (selectedTab) {
                selectedTab.style.display = 'block';
            }
            
            // Tab-specific initialization
            if (tabName === 'plan') loadLocations();
            if (tabName === 'navigation') {
                if (!map) {
                    setTimeout(() => {
                        initMap();
                    }, 100);
                } else {
                    setTimeout(() => {
                        map.invalidateSize();
                    }, 100);
                }
            }
            if (tabName === 'safety') populateSafetyRouteSelect();
        }

        async function loadLocations() {
            try {
                const response = await fetch('/api/locations');
                allLocations = await response.json();
                populateLocationDropdowns();
            } catch (error) {
                console.error('Error loading locations:', error);
            }
        }

        function populateLocationDropdowns() {
            const fromSelect = document.getElementById('fromLocation');
            const toSelect = document.getElementById('toLocation');
            
            fromSelect.innerHTML = '<option value="">Select your current location</option>';
            toSelect.innerHTML = '<option value="">Select your destination</option>';
            
            allLocations.forEach(location => {
                const optionFrom = document.createElement('option');
                optionFrom.value = location.key;
                optionFrom.textContent = location.address;
                fromSelect.appendChild(optionFrom);
                
                const optionTo = document.createElement('option');
                optionTo.value = location.key;
                optionTo.textContent = location.address;
                toSelect.appendChild(optionTo);
            });
        }

        function populateSafetyRouteSelect() {
            const safetySelect = document.getElementById('safetyRouteSelect');
            safetySelect.innerHTML = '<option value="">Select a route to analyze</option>';
            
            allRoutes.forEach(route => {
                const option = document.createElement('option');
                option.value = route.routeId;
                option.textContent = route.name + ' - ' + route.formattedDistance + ' - Safety: ' + route.safetyScore + '/100';
                safetySelect.appendChild(option);
            });
        }

        async function findRoutes() {
            const fromLocation = document.getElementById('fromLocation').value;
            const toLocation = document.getElementById('toLocation').value;
            
            if (!fromLocation || !toLocation) {
                alert('Please select both current location and destination');
                return;
            }
            
            try {
                document.getElementById('routesList').innerHTML = '<p>🔍 Finding safe routes in Erode...</p>';
                
                const response = await fetch(`/api/routes?from=${fromLocation}&to=${toLocation}`);
                
                if (!response.ok) {
                    throw new Error(`HTTP error! status: ${response.status}`);
                }
                
                const routes = await response.json();
                allRoutes = routes;
                
                if (routes.length === 0) {
                    document.getElementById('routesList').innerHTML = `
                        <div class="alert">
                            <p>No routes found in Erode area.</p>
                        </div>
                    `;
                    return;
                }
                
                displayRoutes(routes);
                
            } catch (error) {
                console.error('Error finding routes:', error);
                document.getElementById('routesList').innerHTML = `
                    <div class="alert">
                        <p>Error finding routes: ${error.message}</p>
                    </div>
                `;
            }
        }

        function displayRoutes(routes) {
            const container = document.getElementById('routesList');
            
            container.innerHTML = `
                <h3>Available Safe Routes in Erode</h3>
                <div class="route-options">
            `;
            
            routes.forEach(route => {
                const safetyColor = getSafetyColor(route.safetyScore);
                
                const routeCard = document.createElement('div');
                routeCard.className = 'route-card';
                routeCard.innerHTML = `
                    <div style="border-left: 4px solid ${safetyColor}; padding-left: 15px;">
                        <h4>${route.name}</h4>
                        <div style="display: flex; justify-content: space-between; margin: 10px 0;">
                            <div>
                                <strong>📍 From:</strong><br>${route.startLocation.address}
                            </div>
                            <div style="text-align: right;">
                                <strong>🎯 To:</strong><br>${route.endLocation.address}
                            </div>
                        </div>
                        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin: 15px 0;">
                            <div><strong>📏 Distance:</strong><br>${route.formattedDistance}</div>
                            <div><strong>⏱️ Duration:</strong><br>${route.formattedDuration}</div>
                            <div><strong>🛡️ Safety:</strong><br>${route.safetyEmoji} ${route.safetyLevel}</div>
                            <div><strong>⭐ Score:</strong><br>${route.safetyScore}/100</div>
                        </div>
                        <div style="margin: 10px 0;">
                            <strong>🔍 Safety Factors:</strong><br>
                            <div style="font-size: 12px; color: #666; margin-top: 5px;">
                                ${route.safetyFactors?.map(factor => `• ${factor}`).join('<br>') || 'Standard Erode route'}
                            </div>
                        </div>
                        <button class="btn" onclick="startNavigation('${route.routeId}')" 
                                style="width: 100%; margin-top: 10px; background: ${safetyColor};">
                            🚗 Start Safe Navigation
                        </button>
                        <button class="btn secondary" onclick="analyzeRouteSafetyById('${route.routeId}')" 
                                style="width: 100%; margin-top: 5px;">
                            🛡️ Safety Analysis
                        </button>
                    </div>
                `;
                container.appendChild(routeCard);
            });
            
            container.innerHTML += '</div>';
        }

        function getSafetyColor(score) {
            if (score >= 80) return '#27ae60';
            if (score >= 60) return '#3498db';
            if (score >= 40) return '#f39c12';
            return '#e74c3c';
        }

        async function startNavigation(routeId) {
            try {
                console.log('Starting navigation for route:', routeId);
                
                const response = await fetch('/api/navigation/start', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ 
                        routeId: routeId, 
                        userId: 'user_' + Date.now() 
                    })
                });
                
                if (!response.ok) {
                    const errorData = await response.json();
                    throw new Error(errorData.message || 'Failed to start navigation');
                }
                
                const result = await response.json();
                currentNavigationSession = result.sessionId;
                
                showTab('navigation', document.querySelector('.tab[onclick*="navigation"]'));
                startNavigationUpdates(currentNavigationSession);
                
            } catch (error) {
                console.error('Error starting navigation:', error);
                alert('Failed to start navigation: ' + error.message);
            }
        }

        function startNavigationUpdates(sessionId) {
            if (navigationInterval) {
                clearInterval(navigationInterval);
            }
            
            updateNavigationDisplay(sessionId);
            navigationInterval = setInterval(() => {
                updateNavigationDisplay(sessionId);
            }, 3000);
        }

        async function updateNavigationDisplay(sessionId) {
            try {
                const response = await fetch(`/api/navigation/progress?sessionId=${sessionId}`);
                if (!response.ok) {
                    throw new Error('Navigation session not found');
                }
                
                const session = await response.json();
                updateNavigationUI(session);
                updateMap(session);
                
                if (session.nextVoiceInstruction && voiceAssistantEnabled) {
                    speakInstruction(session.nextVoiceInstruction);
                }
                
                if (!session.isActive) {
                    clearInterval(navigationInterval);
                    speakInstruction("You have arrived at your destination in Erode");
                }
                
            } catch (error) {
                console.error('Error updating navigation:', error);
                clearInterval(navigationInterval);
            }
        }

        function updateNavigationUI(session) {
            document.getElementById('distanceRemaining').textContent = session.remainingDistance ? session.remainingDistance.toFixed(1) + ' km' : '-- km';
            document.getElementById('timeRemaining').textContent = session.remainingTime ? session.remainingTime + ' min' : '-- min';
            document.getElementById('currentSpeed').textContent = session.speed ? session.speed.toFixed(1) + ' km/h' : '-- km/h';
            document.getElementById('safetyLevel').innerHTML = session.safetyAnalysis ? 
                session.safetyAnalysis.safetyEmoji + ' ' + session.safetyAnalysis.safetyDisplayName : '--';
            
            document.getElementById('currentInstruction').textContent = session.currentInstruction || 'No instruction available';
            
            const alertsContainer = document.getElementById('safetyAlerts');
            if (session.safetyAnalysis && session.safetyAnalysis.safetyReports && session.safetyAnalysis.safetyReports.length > 0) {
                alertsContainer.innerHTML = '<h4>Safety Alerts</h4>';
                session.safetyAnalysis.safetyReports.forEach(report => {
                    const alertDiv = document.createElement('div');
                    alertDiv.className = 'safety-alert';
                    alertDiv.innerHTML = `${report.emoji || '⚠️'} ${report.type}: ${report.description}`;
                    alertsContainer.appendChild(alertDiv);
                });
            } else {
                alertsContainer.innerHTML = '';
            }
        }

        function updateMap(session) {
            if (!map) {
                console.log('Map not initialized, initializing now...');
                initMap();
                if (!map) return;
            }
            
            if (routeLayer) routeLayer.clearLayers();
            if (markerLayer) markerLayer.clearLayers();
            
            const routePath = session.routePath || [];
            const currentLocation = session.currentLocation;
            
            if (routePath.length > 1) {
                const latLngs = routePath.map(point => [point.lat, point.lng]);
                L.polyline(latLngs, {
                    color: '#27ae60',
                    weight: 6,
                    opacity: 0.8,
                    lineJoin: 'round'
                }).addTo(routeLayer);
            }
            
            if (routePath.length > 0) {
                L.marker([routePath[0].lat, routePath[0].lng]).bindPopup('Start: ' + routePath[0].address).addTo(markerLayer);
                L.marker([routePath[routePath.length - 1].lat, routePath[routePath.length - 1].lng]).bindPopup('Destination: ' + routePath[routePath.length - 1].address).addTo(markerLayer);
                
                if (currentLocation) {
                    L.marker([currentLocation.lat, currentLocation.lng], {
                        icon: L.divIcon({
                            className: 'current-location-marker',
                            html: '<div style="background: #e74c3c; width: 16px; height: 16px; border-radius: 50%; border: 3px solid white; box-shadow: 0 0 10px rgba(0,0,0,0.5);"></div>',
                            iconSize: [22, 22],
                            iconAnchor: [11, 11]
                        })
                    }).bindPopup('Current Location').addTo(markerLayer);
                }
            }
            
            if (routePath.length > 0) {
                const bounds = L.latLngBounds(routePath.map(point => [point.lat, point.lng]));
                map.fitBounds(bounds, { padding: [20, 20] });
            }
        }

        function speakInstruction(instruction) {
            if (!voiceAssistantEnabled || !instruction) return;
            
            if (speechSynthesis.speaking) {
                speechSynthesis.cancel();
            }
            
            const utterance = new SpeechSynthesisUtterance(instruction);
            utterance.rate = 0.9;
            utterance.pitch = 1.0;
            utterance.volume = 0.8;
            
            // Add error handling for speech synthesis
            utterance.onerror = function(event) {
                console.error('Speech synthesis error:', event);
            };
            
            speechSynthesis.speak(utterance);
        }

        function toggleVoiceAssistant() {
            voiceAssistantEnabled = !voiceAssistantEnabled;
            const voiceIcon = document.getElementById('voiceIcon');
            const voiceStatus = document.getElementById('voiceStatus');
            
            if (voiceAssistantEnabled) {
                voiceIcon.textContent = '🔊';
                voiceStatus.textContent = 'Voice Assistant: ON';
            } else {
                voiceIcon.textContent = '🔇';
                voiceStatus.textContent = 'Voice Assistant: OFF';
                speechSynthesis.cancel();
            }
        }

        function repeatInstruction() {
            const currentInstruction = document.getElementById('currentInstruction').textContent;
            if (currentInstruction && currentInstruction !== 'Start navigation to see instructions') {
                speakInstruction(currentInstruction);
            }
        }

        async function stopNavigation() {
            if (!currentNavigationSession) return;
            
            try {
                await fetch('/api/navigation/stop', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ sessionId: currentNavigationSession })
                });
                
                clearInterval(navigationInterval);
                currentNavigationSession = null;
                
                if (routeLayer) routeLayer.clearLayers();
                if (markerLayer) markerLayer.clearLayers();
                
                document.getElementById('navInfo').innerHTML = '<p>Navigation stopped.</p>';
                
            } catch (error) {
                console.error('Error stopping navigation:', error);
            }
        }

        async function analyzeRouteSafety() {
            const routeId = document.getElementById('safetyRouteSelect').value;
            if (!routeId) {
                alert('Please select a route to analyze');
                return;
            }
            await analyzeRouteSafetyById(routeId);
        }

        async function analyzeRouteSafetyById(routeId) {
            try {
                document.getElementById('safetyAnalysisResult').innerHTML = '<p>🔍 Analyzing route safety...</p>';
                
                const response = await fetch(`/api/safety/analysis?routeId=${routeId}`);
                if (!response.ok) {
                    throw new Error('Failed to analyze route safety');
                }
                
                const analysis = await response.json();
                displaySafetyAnalysis(analysis);
                
            } catch (error) {
                console.error('Error analyzing route safety:', error);
                document.getElementById('safetyAnalysisResult').innerHTML = `
                    <div class="alert">
                        <p>Error analyzing route safety: ${error.message}</p>
                    </div>
                `;
            }
        }

        function displaySafetyAnalysis(analysis) {
            const container = document.getElementById('safetyAnalysisResult');
            
            container.innerHTML = `
                <div class="route-card" style="border-left-color: ${analysis.colorCode || '#95a5a6'}">
                    <h3>${analysis.safetyEmoji || '🛡️'} Safety Analysis: ${analysis.safetyDisplayName || 'Unknown'}</h3>
                    <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 15px; margin: 15px 0;">
                        <div><strong>Base Safety Score:</strong><br>${analysis.baseScore || 0}/100</div>
                        <div><strong>Adjusted Score:</strong><br>${analysis.adjustedScore || 0}/100</div>
                    </div>
                    
                    <h4>📋 Safety Recommendations</h4>
                    <div style="background: #e8f4fd; padding: 12px; border-radius: 8px; margin: 10px 0;">
                        ${(analysis.recommendations || []).map(rec => `<p>• ${rec}</p>`).join('')}
                    </div>
                    
                    ${analysis.safetyReports && analysis.safetyReports.length > 0 ? `
                        <h4>⚠️ Safety Reports in Area</h4>
                        <div style="margin: 10px 0;">
                            ${analysis.safetyReports.map(report => `
                                <div class="safety-alert">
                                    <strong>${report.type || 'Unknown'}</strong>: ${report.description || 'No description'}<br>
                                    <small>Severity: ${report.severity || 0}/100 - ${report.safetyLevel || 'Unknown'}</small>
                                </div>
                            `).join('')}
                        </div>
                    ` : ''}
                    
                    ${analysis.alternativeRoutes && analysis.alternativeRoutes.length > 0 ? `
                        <h4>🔄 Alternative Safer Routes</h4>
                        <div style="margin: 10px 0;">
                            ${analysis.alternativeRoutes.map(route => `
                                <div class="segment-card">
                                    <strong>${route.name || 'Unknown Route'}</strong><br>
                                    ${route.formattedDistance || '0 km'} • ${route.formattedDuration || '0 min'}<br>
                                    Safety: ${route.safetyEmoji || '🛡️'} ${route.safetyLevel || 'Unknown'} (${route.safetyScore || 0}/100)
                                    <button class="btn" onclick="startNavigation('${route.routeId}')" 
                                            style="margin-top: 5px; padding: 5px 10px; font-size: 0.8rem;">
                                        Use This Route
                                    </button>
                                </div>
                            `).join('')}
                        </div>
                    ` : ''}
                </div>
            `;
        }

        // Initialize the app when DOM is loaded
        document.addEventListener('DOMContentLoaded', function() {
            initApp();
        });
    </script>
</body>
</html>
""";
        }
    }
    
    private static void sendJsonResponse(HttpExchange exchange, Object data) throws IOException {
        String response = toJson(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void sendError(HttpExchange exchange, String message) throws IOException {
        Map<String, Object> error = new HashMap<>();
        error.put("error", true);
        error.put("message", message);
        sendJsonResponse(exchange, error);
    }

    private static String toJson(Object obj) {
        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            String entries = map.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + toJson(entry.getValue()))
                .collect(Collectors.joining(","));
            return "{" + entries + "}";
        } else if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            String elements = list.stream()
                .map(SafePathWebApp::toJson)
                .collect(Collectors.joining(","));
            return "[" + elements + "]";
        } else if (obj instanceof String) {
            return "\"" + ((String) obj).replace("\"", "\\\"") + "\"";
        } else {
            return String.valueOf(obj);
        }
    }

    private static Map<String, Object> parseJson(String json) {
        Map<String, Object> result = new HashMap<>();
        if (json != null && json.startsWith("{") && json.endsWith("}")) {
            String content = json.substring(1, json.length() - 1);
            String[] pairs = content.split(",");
            for (String pair : pairs) {
                String[] keyValue = pair.split(":", 2);
                if (keyValue.length == 2) {
                    String key = keyValue[0].trim().replace("\"", "");
                    String value = keyValue[1].trim().replace("\"", "");
                    result.put(key, value);
                }
            }
        }
        return result;
    }

    private static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] entry = param.split("=");
                if (entry.length > 1) {
                    result.put(entry[0], URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
                } else {
                    result.put(entry[0], "");
                }
            }
        }
        return result;
    }

    public static void main(String[] args) throws IOException {
        String host = "0.0.0.0"; 
        int port = 8080;
        
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        
        server.createContext("/", new StaticHandler());
        server.createContext("/api/routes", new RoutesHandler());
        server.createContext("/api/locations", new LocationsHandler());
        server.createContext("/api/navigation", new NavigationHandler());
        server.createContext("/api/safety", new SafetyHandler());
        server.createContext("/api/gps", new GPSHandler());
        server.createContext("/api/location/current", new CurrentLocationHandler());
        server.createContext("/api/network-info", new NetworkInfoHandler());
        
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        
        String localIP = getLocalIP();
        
        System.out.println("🚀 SafePath Web App Started!");
        System.out.println("📍 Server Access URLs:");
        System.out.println("   💻 Local: http://localhost:" + port);
        System.out.println("   🌐 Network: http://" + localIP + ":" + port);
        System.out.println("");
        System.out.println("🛡️  Safety Features:");
        System.out.println("   • Real-time route safety analysis");
        System.out.println("   • Safety level scoring (0-100)");
        System.out.println("   • Safety recommendations");
        System.out.println("   • Alternative safe routes");
        System.out.println("   • Safety report system");
        System.out.println("");
        System.out.println("🗺️  Erode Coverage:");
        System.out.println("   • Erode City Center & Landmarks");
        System.out.println("   • Railway Station, Bus Stand, Markets");
        System.out.println("   • Perundurai, Bhavani, Gobichettipalayam");
        System.out.println("   • Sathyamangalam areas");
        System.out.println("");
        System.out.println("✅ SafePath navigation system ready!");
    }

    private static String getLocalIP() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "YOUR_LOCAL_IP";
        }
    }
}