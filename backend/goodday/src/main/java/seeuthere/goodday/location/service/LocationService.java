package seeuthere.goodday.location.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import seeuthere.goodday.location.domain.algorithm.DuplicateStationRemover;
import seeuthere.goodday.location.domain.algorithm.PathResult;
import seeuthere.goodday.location.domain.algorithm.StationGrades;
import seeuthere.goodday.location.dto.PathTransferResult;
import seeuthere.goodday.location.domain.combiner.AxisKeywordCombiner;
import seeuthere.goodday.location.domain.location.MiddlePoint;
import seeuthere.goodday.location.domain.location.Point;
import seeuthere.goodday.location.domain.location.Points;
import seeuthere.goodday.location.domain.location.WeightStations;
import seeuthere.goodday.location.domain.requester.CoordinateRequester;
import seeuthere.goodday.location.domain.requester.LocationRequester;
import seeuthere.goodday.location.domain.requester.SearchRequester;
import seeuthere.goodday.location.domain.requester.UtilityRequester;
import seeuthere.goodday.location.dto.api.response.APIAxisDocument;
import seeuthere.goodday.location.dto.api.response.APILocationDocument;
import seeuthere.goodday.location.dto.api.response.APIUtilityDocument;
import seeuthere.goodday.location.dto.request.LocationsRequest;
import seeuthere.goodday.location.dto.response.LocationResponse;
import seeuthere.goodday.location.dto.response.MiddlePointResponse;
import seeuthere.goodday.location.dto.response.SpecificLocationResponse;
import seeuthere.goodday.location.dto.response.UtilityResponse;
import seeuthere.goodday.location.repository.PathResultRedisRepository;
import seeuthere.goodday.location.util.LocationCategory;
import seeuthere.goodday.path.domain.PointWithName;
import seeuthere.goodday.path.service.PathService;

@Service
public class LocationService {

    private final CoordinateRequester coordinateRequester;
    private final LocationRequester locationRequester;
    private final SearchRequester searchRequester;
    private final UtilityRequester utilityRequester;
    private final PathService pathService;
    private final PathResultRedisRepository pathResultRedisRepository;
    private final WeightStations weightStations;

    public LocationService(
        CoordinateRequester coordinateRequester, LocationRequester locationRequester,
        SearchRequester searchRequester, UtilityRequester utilityRequester,
        PathService pathService, PathResultRedisRepository pathResultRedisRepository,
        WeightStations weightStations) {
        this.coordinateRequester = coordinateRequester;
        this.locationRequester = locationRequester;
        this.searchRequester = searchRequester;
        this.utilityRequester = utilityRequester;
        this.pathService = pathService;
        this.pathResultRedisRepository = pathResultRedisRepository;
        this.weightStations = weightStations;
    }

    public List<SpecificLocationResponse> findAddress(double x, double y) {
        List<APILocationDocument> apiLocationDocuments = locationRequester.requestAddress(x, y);

        return toSpecificLocationResponse(apiLocationDocuments);
    }

    private List<SpecificLocationResponse> toSpecificLocationResponse(
        List<APILocationDocument> apiLocationDocuments) {
        return apiLocationDocuments.stream()
            .map(SpecificLocationResponse::new)
            .collect(Collectors.toList());
    }

    public List<LocationResponse> findAxis(String address) {
        AxisKeywordCombiner axisKeywordCombiner = combinedAxisKeywordCombiner(
            address, coordinateRequester, searchRequester);

        return toLocationResponse(axisKeywordCombiner);
    }

    private AxisKeywordCombiner combinedAxisKeywordCombiner(String address,
        CoordinateRequester coordinateRequester, SearchRequester searchRequester) {
        List<APIAxisDocument> exactAddressResult = coordinateRequester.requestCoordinate(address);
        List<APIUtilityDocument> keyWordResult = searchRequester.requestSearch(address);

        return AxisKeywordCombiner.valueOf(exactAddressResult,
            keyWordResult);
    }

    private List<LocationResponse> toLocationResponse(AxisKeywordCombiner axisKeywordCombiner) {
        return axisKeywordCombiner.getLocations()
            .stream()
            .map(LocationResponse::new)
            .collect(Collectors.toList());
    }

    public List<UtilityResponse> findUtility(String category, double x, double y) {
        String categoryCode = LocationCategory.translatedCode(category);
        List<APIUtilityDocument> apiUtilityDocuments = utilityRequester
            .requestUtility(categoryCode, x, y);
        return toUtilityResponse(apiUtilityDocuments);
    }

    public List<UtilityResponse> findSearch(String keyword) {
        List<APIUtilityDocument> apiUtilityDocuments = searchRequester.requestSearch(keyword);
        return toUtilityResponse(apiUtilityDocuments);
    }

    private List<UtilityResponse> toUtilityResponse(List<APIUtilityDocument> apiUtilityDocuments) {
        return apiUtilityDocuments.stream()
            .map(UtilityResponse::new)
            .collect(Collectors.toList());
    }

    public MiddlePointResponse findMiddlePoint(LocationsRequest locationsRequest) {
        Points points = Points.valueOf(locationsRequest);
        MiddlePoint middlePoint = MiddlePoint.valueOf(points);

        List<UtilityResponse> utilityResponses = findSubway(middlePoint.getX(), middlePoint.getY());
        Set<String> keys = weightStations.getKeys();
        for (String key: keys) {
            Point point = weightStations.get(key);
            UtilityResponse utilityResponse = new UtilityResponse.Builder()
                .placeName(key)
                .x(point.getX())
                .y(point.getY())
                .build();
            utilityResponses.add(utilityResponse);
        }

        DuplicateStationRemover duplicateStationRemover = new DuplicateStationRemover(
            utilityResponses);
        List<UtilityResponse> result = duplicateStationRemover.result();

        Map<Point, Map<Point, PathResult>> responsesFromPoint
            = getPointsToPath(points, result);

        StationGrades stationGrades
            = StationGrades.valueOf(points, result, responsesFromPoint);

        UtilityResponse finalResponse = stationGrades.finalUtilityResponse();
        return new MiddlePointResponse(finalResponse.getX(), finalResponse.getY());
    }

    private Map<Point, Map<Point, PathResult>> getPointsToPath(Points points,
        List<UtilityResponse> utilityResponses) {
        Map<Point, Map<Point, PathResult>> responsesFromPoint = new HashMap<>();

        for (UtilityResponse response : utilityResponses) {
            calculateSource(points, responsesFromPoint, response);
        }
        return responsesFromPoint;
    }

    private void calculateSource(Points points,
        Map<Point, Map<Point, PathResult>> responsesFromPoint, UtilityResponse response) {

        final Point target = new Point(response.getX(), response.getY());

        List<PathTransferResult> pathTransferResults = points.getPoints().parallelStream()
            .map((source) -> new PathTransferResult(
                source,
                target,
                pathResultRedisRepository.findById(source.toString() + target)
                .orElseGet(() -> saveRedisCachePathResult(source, target, response.getPlaceName())))
            )
            .collect(Collectors.toList());

        for (PathTransferResult pathTransferResult : pathTransferResults) {
            Map<Point, PathResult> responses
                = responsesFromPoint.getOrDefault(pathTransferResult.getSource(), new HashMap<>());
            responses.put(pathTransferResult.getTarget(), pathTransferResult.getPathResult());
            responsesFromPoint.put(pathTransferResult.getSource(), responses);
        }
    }

    private PathResult saveRedisCachePathResult(Point source, Point target, String placeName) {
        PathResult result = minPathResult(source, target, placeName);
        pathResultRedisRepository.save(result);
        return result;
    }

    private PathResult minPathResult(Point source, Point target, String placeName) {
        PointWithName startPointWithName = new PointWithName(source, "출발점");
        PointWithName endPointWithName = new PointWithName(target, "도착점");

        PathResult subwayResult = PathResult
            .pathsResponseToPathResult(source, target,
                pathService.findSubwayPath(startPointWithName, endPointWithName),
                weightStations.contains(placeName));

        PathResult busSubwayResult = PathResult
            .pathsResponseToPathResult(source, target,
                pathService.findTransferPath(startPointWithName, endPointWithName),
                weightStations.contains(placeName));

        return PathResult.minTimePathResult(subwayResult, busSubwayResult);
    }

    private List<UtilityResponse> findSubway( double x, double y) {
        List<APIUtilityDocument> apiUtilityDocuments = utilityRequester.requestSubway(x, y);
        return toUtilityResponse(apiUtilityDocuments);
    }
}
