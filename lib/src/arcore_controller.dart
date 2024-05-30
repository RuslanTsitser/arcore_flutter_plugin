import 'package:arcore_flutter_plugin/src/arcore_augmented_image.dart';
import 'package:arcore_flutter_plugin/src/arcore_rotating_node.dart';
import 'package:flutter/services.dart';
import 'package:meta/meta.dart';
import 'package:vector_math/vector_math_64.dart';

import 'arcore_hit_test_result.dart';
import 'arcore_node.dart';
import 'arcore_plane.dart';

typedef StringResultHandler = void Function(String text);
typedef UnsupportedHandler = void Function(String text);
typedef ArCoreHitResultHandler = void Function(List<ArCoreHitTestResult> hits);
typedef ArCorePlaneHandler = void Function(ArCorePlane plane);
typedef ArCoreAugmentedImageTrackingHandler = void Function(ArCoreAugmentedImage);

const UTILS_CHANNEL_NAME = 'arcore_flutter_plugin/utils';

class ArCoreController {
  static checkArCoreAvailability() async {
    final bool arcoreAvailable = await MethodChannel(UTILS_CHANNEL_NAME).invokeMethod('checkArCoreApkAvailability');
    return arcoreAvailable;
  }

  static checkIsArCoreInstalled() async {
    final bool arcoreInstalled = await MethodChannel(UTILS_CHANNEL_NAME).invokeMethod('checkIfARCoreServicesInstalled');
    return arcoreInstalled;
  }

  ArCoreController({
    required this.id,
    this.enableTapRecognizer,
    this.enablePlaneRenderer,
    this.enableUpdateListener,
    this.debug = false,
  }) {
    _channel = MethodChannel('arcore_flutter_plugin_$id');
    _channel.setMethodCallHandler(_handleMethodCalls);
    init();
  }

  final int id;
  final bool? enableUpdateListener;
  final bool? enableTapRecognizer;
  final bool? enablePlaneRenderer;
  final bool? debug;
  late MethodChannel _channel;
  StringResultHandler? onError;
  StringResultHandler? onNodeTap;

//  UnsupportedHandler onUnsupported;
  ArCoreHitResultHandler? onPlaneTap;
  ArCorePlaneHandler? onPlaneDetected;
  String trackingState = '';
  ArCoreAugmentedImageTrackingHandler? onTrackingImage;

  init() async {
    try {
      await _channel.invokeMethod<void>('init', {
        'enableTapRecognizer': enableTapRecognizer,
        'enablePlaneRenderer': enablePlaneRenderer,
        'enableUpdateListener': enableUpdateListener,
      });
    } on PlatformException catch (ex) {
      print(ex.message);
    }
  }

  Future<dynamic> _handleMethodCalls(MethodCall call) async {
    if (debug ?? true) {
      print('_platformCallHandler call ${call.method} ${call.arguments}');
    }

    switch (call.method) {
      case 'onError':
        if (onError != null) {
          onError!(call.arguments);
        }
        break;
      case 'onNodeTap':
        if (onNodeTap != null) {
          onNodeTap!(call.arguments);
        }
        break;
      case 'onPlaneTap':
        if (onPlaneTap != null) {
          final List<dynamic> input = call.arguments;
          final objects = input
              .cast<Map<dynamic, dynamic>>()
              .map<ArCoreHitTestResult>((Map<dynamic, dynamic> h) => ArCoreHitTestResult.fromMap(h))
              .toList();
          onPlaneTap!(objects);
        }
        break;
      case 'onPlaneDetected':
        if (enableUpdateListener ?? true && onPlaneDetected != null) {
          final plane = ArCorePlane.fromMap(call.arguments);
          onPlaneDetected!(plane);
        }
        break;
      case 'getTrackingState':
        // TRACKING, PAUSED or STOPPED
        trackingState = call.arguments;
        if (debug ?? true) {
          print('Latest tracking state received is: $trackingState');
        }
        break;
      case 'onTrackingImage':
        if (debug ?? true) {
          print('flutter onTrackingImage');
        }
        final arCoreAugmentedImage = ArCoreAugmentedImage.fromMap(call.arguments);
        onTrackingImage!(arCoreAugmentedImage);
        break;
      case 'togglePlaneRenderer':
        if (debug ?? true) {
          print('Toggling Plane Renderer Visibility');
        }
        togglePlaneRenderer();
        break;

      default:
        if (debug ?? true) {
          print('Unknown method ${call.method}');
        }
    }
    return Future.value();
  }

  Future<void> addArCoreNode(ArCoreNode node, {String? parentNodeName}) {
    final params = _addParentNodeNameToParams(node.toMap(), parentNodeName);
    if (debug ?? true) {
      print(params.toString());
    }
    _addListeners(node);
    return _channel.invokeMethod('addArCoreNode', params);
  }

  Vector3 getCameraPosition() {
    final List<double> result = _channel.invokeMethod('getCameraPosition') as List<double>;

    final x = result[0].toDouble();
    final y = result[1].toDouble();
    final z = result[2].toDouble();

    final cameraPositionVector = Vector3(x, y, z);
    return cameraPositionVector;

    // Handle errors
  }

  Vector3 getNodePosition({required String name}) {
    final List<double> result = _channel.invokeMethod('getNodePosition', name) as List<double>;
    final x = result[0].toDouble();
    final y = result[1].toDouble();
    final z = result[2].toDouble();

    final cameraPositionVector = Vector3(x, y, z);
    return cameraPositionVector;

    // Handle errors
  }

  Future<dynamic> togglePlaneRenderer() async {
    return _channel.invokeMethod('togglePlaneRenderer');
  }

  Future<dynamic> getTrackingState() async {
    return _channel.invokeMethod('getTrackingState');
  }

  Future<void> addArCoreNodeToAugmentedImage(ArCoreNode node, int index, {String? parentNodeName}) {
    final params = _addParentNodeNameToParams(node.toMap(), parentNodeName);
    _addListeners(node);
    return _channel.invokeMethod('attachObjectToAugmentedImage', {'index': index, 'node': params});
  }

  Future<void> addArCoreNodeWithAnchor(ArCoreNode node, {String? parentNodeName}) {
    final params = _addParentNodeNameToParams(node.toMap(), parentNodeName);
    if (debug ?? true) {
      print(params.toString());
    }
    _addListeners(node);
    if (debug ?? true) {
      print('---------_CALLING addArCoreNodeWithAnchor : $params');
    }
    return _channel.invokeMethod('addArCoreNodeWithAnchor', params);
  }

  Future<void> removeNode({required ArCoreNode node}) async {
    await _channel.invokeMethod('removeARCoreNode', {'nodeName': node.name});
    _disposeNode(node);
  }

  Map<String, dynamic>? _addParentNodeNameToParams(Map<String, dynamic> geometryMap, String? parentNodeName) {
    if (parentNodeName != null && parentNodeName.isNotEmpty) geometryMap['parentNodeName'] = parentNodeName;
    return geometryMap;
  }

  void _disposeNode(ArCoreNode node) {
    node.scale?.dispose();
    node.position?.dispose();
    node.rotation?.dispose();
    node.shape?.materials.dispose();
    if (node is ArCoreRotatingNode) {
      node.degreesPerSecond.dispose();
    }
  }

  void _addListeners(ArCoreNode node) {
    node.scale?.addListener(() => _handleScaleChanged(node));
    node.position?.addListener(() => _handlePositionChanged(node));
    node.shape?.materials.addListener(() => _updateMaterials(node));

    if (node is ArCoreRotatingNode) {
      node.degreesPerSecond.addListener(() => _handleRotationChanged(node));
    }
  }

  void _handleScaleChanged(ArCoreNode node) {
    _channel.invokeMethod<void>(
      'scaleChanged',
      {
        'name': node.name,
        'scale': {
          'x': node.scale?.value.x,
          'y': node.scale?.value.y,
          'z': node.scale?.value.z,
        },
      },
    );
  }

  void _handlePositionChanged(ArCoreNode node) {
    _channel.invokeMethod<void>(
      'positionChanged',
      {
        'name': node.name,
        'position': {
          'x': node.position?.value.x,
          'y': node.position?.value.y,
          'z': node.position?.value.z,
        },
      },
    );
  }

  void _handleRotationChanged(ArCoreRotatingNode node) {
    _channel
        .invokeMethod<void>('rotationChanged', {'name': node.name, 'degreesPerSecond': node.degreesPerSecond.value});
  }

  void _updateMaterials(ArCoreNode node) {
    _channel.invokeMethod<void>('updateMaterials', _getHandlerParams(node, node.shape!.toMap()));
  }

  Map<String, dynamic> _getHandlerParams(ArCoreNode node, Map<String, dynamic>? params) {
    final Map<String, dynamic> values = <String, dynamic>{'name': node.name}..addAll(params!);
    return values;
  }

  Future<void> loadSingleAugmentedImage({required Uint8List bytes, double? imageWidth}) {
    return _channel.invokeMethod('load_single_image_on_db', {
      'bytes': bytes,
      if (imageWidth != null) 'imageWidth': imageWidth,
    });
  }

  Future<Uint8List> takePicture() async {
    return await _channel.invokeMethod('takePicture');
  }

  Future<void> loadMultipleAugmentedImage({@required Map<String, Uint8List>? bytesMap}) {
    assert(bytesMap != null);
    return _channel.invokeMethod('load_multiple_images_on_db', {
      'bytesMap': bytesMap,
    });
  }

  Future<void> loadAugmentedImagesDatabase({@required Uint8List? bytes}) {
    assert(bytes != null);
    return _channel.invokeMethod('load_augmented_images_database', {
      'bytes': bytes,
    });
  }

  void dispose() {
    _channel.invokeMethod<void>('dispose');
  }

  void resume() {
    _channel.invokeMethod<void>('resume');
  }

  Future<void> removeNodeWithIndex(int index) async {
    try {
      return await _channel.invokeMethod('removeARCoreNodeWithIndex', {
        'index': index,
      });
    } catch (ex) {
      print(ex);
    }
  }
}
