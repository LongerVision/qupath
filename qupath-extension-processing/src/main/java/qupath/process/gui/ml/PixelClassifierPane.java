package qupath.process.gui.ml;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_ml.ANN_MLP;
import org.bytedeco.opencv.opencv_ml.KNearest;
import org.bytedeco.opencv.opencv_ml.LogisticRegression;
import org.bytedeco.opencv.opencv_ml.RTrees;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.CompositeImage;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.util.Callback;
import qupath.imagej.gui.IJExtension;
import qupath.imagej.tools.IJTools;
import qupath.lib.classifiers.Normalization;
import qupath.lib.classifiers.PathClassifierTools;
import qupath.lib.classifiers.pixel.PixelClassificationImageServer;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.classifiers.pixel.PixelClassifierMetadata;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.charts.ChartTools;
import qupath.lib.gui.commands.MiniViewers;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.tools.ColorToolsFX;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.gui.tools.PaneTools;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.images.servers.ImageServerMetadata.ChannelType;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RectangleROI;
import qupath.opencv.ml.FeaturePreprocessor;
import qupath.opencv.ml.OpenCVClassifiers;
import qupath.opencv.ml.OpenCVClassifiers.OpenCVStatModel;
import qupath.opencv.ml.OpenCVClassifiers.RTreesClassifier;
import qupath.opencv.ml.pixel.PixelClassifiers;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;
import qupath.process.gui.ml.PixelClassifierTraining.ClassifierTrainingData;

/**
 * Main user interface for interactively training a {@link PixelClassifier}.
 * 
 * @author Pete Bankhead
 */
public class PixelClassifierPane {
	
	final static Logger logger = LoggerFactory.getLogger(PixelClassifierPane.class);
	
	private static ObservableList<ImageDataTransformerBuilder> defaultFeatureCalculatorBuilders = FXCollections.observableArrayList();
	
	
	private QuPathViewer viewer;
	private GridPane pane;
	
	private ObservableList<ClassificationResolution> resolutions = FXCollections.observableArrayList();
	private ComboBox<ClassificationResolution> comboResolutions = new ComboBox<>(resolutions);
	private ReadOnlyObjectProperty<ClassificationResolution> selectedResolution;
	
	// To display features as overlays across the image
	private ComboBox<String> comboDisplayFeatures = new ComboBox<>();
	private Slider sliderFeatureOpacity = new Slider(0.0, 1.0, 1.0);
	private Spinner<Double> spinFeatureMin = new Spinner<>(-Double.MAX_VALUE, Double.MAX_VALUE, 0);
	private Spinner<Double> spinFeatureMax = new Spinner<>(-Double.MAX_VALUE, Double.MAX_VALUE, 1.0);
	private String DEFAULT_CLASSIFICATION_OVERLAY = "Show classification";

	
	private MiniViewers.MiniViewerManager miniViewer;
	
	private BooleanProperty livePrediction = new SimpleBooleanProperty(false);
	
	private ObservableBooleanValue classificationComplete = new SimpleBooleanProperty(false);
	private ReadOnlyObjectProperty<OpenCVStatModel> selectedClassifier;

	private ReadOnlyObjectProperty<ClassificationRegion> selectedRegion;
	
	private ReadOnlyObjectProperty<ImageDataTransformerBuilder> selectedFeatureCalculatorBuilder;

	private ReadOnlyObjectProperty<ImageServerMetadata.ChannelType> selectedOutputType;
	
	private StringProperty cursorLocation = new SimpleStringProperty();
	
	private PieChart pieChart;

	private HierarchyListener hierarchyListener = new HierarchyListener();

	private ChangeListener<ImageData<BufferedImage>> imageDataListener = new ChangeListener<ImageData<BufferedImage>>() {

		@Override
		public void changed(ObservableValue<? extends ImageData<BufferedImage>> observable,
				ImageData<BufferedImage> oldValue, ImageData<BufferedImage> newValue) {
			if (oldValue != null)
				oldValue.getHierarchy().removePathObjectListener(hierarchyListener);
			if (newValue != null)
				newValue.getHierarchy().addPathObjectListener(hierarchyListener);
			updateTitle();
			updateAvailableResolutions();
		}
		
	};
	
	private Stage stage;
	
	/**
	 * Constructor.
	 * @param viewer the current {@link QuPathViewer} that will be used for interactive training.
	 */
	public PixelClassifierPane(final QuPathViewer viewer) {
		this.viewer = viewer;
		helper = new PixelClassifierTraining(viewer.getImageData(), null);
		featureRenderer = new FeatureRenderer(viewer.getImageRegionStore());
		initialize(viewer);
	}

	
	private void initialize(final QuPathViewer viewer) {
		
		int row = 0;
		
		// Classifier
		pane = new GridPane();
		
		var labelClassifier = new Label("Classifier");
		var comboClassifier = new ComboBox<OpenCVStatModel>();
		labelClassifier.setLabelFor(comboClassifier);
		
		selectedClassifier = comboClassifier.getSelectionModel().selectedItemProperty();
		selectedClassifier.addListener((v, o, n) -> updateClassifier());
		var btnEditClassifier = new Button("Edit");
		btnEditClassifier.setOnAction(e -> editClassifierParameters());
		btnEditClassifier.disableProperty().bind(selectedClassifier.isNull());
		
		PaneTools.addGridRow(pane, row++, 0, 
				"Choose classifier type (RTrees or ANN_MLP are generally good choices)",
				labelClassifier, comboClassifier, comboClassifier, btnEditClassifier);
		
		// Image resolution
		var labelResolution = new Label("Resolution");
		labelResolution.setLabelFor(comboResolutions);
		var btnResolution = new Button("Add");
		btnResolution.setOnAction(e -> addResolution());
		selectedResolution = comboResolutions.getSelectionModel().selectedItemProperty();
		
		PaneTools.addGridRow(pane, row++, 0, 
				"Choose the base image resolution based upon required detail in the classification (see preview on the right)",
				labelResolution, comboResolutions, comboResolutions, btnResolution);
		
		
		// Features
		var labelFeatures = new Label("Features");
		var comboFeatures = new ComboBox<ImageDataTransformerBuilder>();
		comboFeatures.getItems().add(new ImageDataTransformerBuilder.DefaultFeatureCalculatorBuilder(viewer.getImageData()));
//		comboFeatures.getItems().add(new FeatureCalculatorBuilder.ExtractNeighborsFeatureCalculatorBuilder(viewer.getImageData()));
		labelFeatures.setLabelFor(comboFeatures);
		selectedFeatureCalculatorBuilder = comboFeatures.getSelectionModel().selectedItemProperty();
		
//		var labelFeaturesSummary = new Label("No features selected");
		var btnShowFeatures = new Button("Show");
		btnShowFeatures.setOnAction(e -> showFeatures());
		
		var btnCustomizeFeatures = new Button("Edit");
		btnCustomizeFeatures.disableProperty().bind(Bindings.createBooleanBinding(() -> {
			var calc = selectedFeatureCalculatorBuilder.get();
			return calc == null || !calc.canCustomize(viewer.getImageData());
		},
				selectedFeatureCalculatorBuilder));
		btnCustomizeFeatures.setOnAction(e -> {
			if (selectedFeatureCalculatorBuilder.get().doCustomize(viewer.getImageData())) {
				updateFeatureCalculator();
			}
		});
		comboFeatures.getItems().addAll(defaultFeatureCalculatorBuilders);
		
		comboFeatures.getSelectionModel().select(0);
		comboFeatures.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> updateFeatureCalculator());
//		btnCustomizeFeatures.setOnAction(e -> showFeatures());
		
		PaneTools.addGridRow(pane, row++, 0, 
				"Select features for the classifier",
				labelFeatures, comboFeatures, btnCustomizeFeatures, btnShowFeatures);

		
		// Output
		var labelOutput = new Label("Output");
		var comboOutput = new ComboBox<ImageServerMetadata.ChannelType>();
		comboOutput.getItems().addAll(ImageServerMetadata.ChannelType.CLASSIFICATION, ImageServerMetadata.ChannelType.PROBABILITY);
		selectedOutputType = comboOutput.getSelectionModel().selectedItemProperty();
		selectedOutputType.addListener((v, o, n) -> {
			updateClassifier();
		});
		comboOutput.getSelectionModel().clearAndSelect(0);
		var btnShowOutput = new Button("Show");
		btnShowOutput.setOnAction(e -> showOutput());
		
		PaneTools.addGridRow(pane, row++, 0, 
				"Choose whether to output classifications only, or estimated probabilities per class (not all classifiers support probabilities, which also require more memory)",
				labelOutput, comboOutput, comboOutput, btnShowOutput);
		
		
		// Region
		var labelRegion = new Label("Region");
		var comboRegion = new ComboBox<ClassificationRegion>();
		comboRegion.getItems().addAll(ClassificationRegion.values());
		selectedRegion = comboRegion.getSelectionModel().selectedItemProperty();
		selectedRegion.addListener((v, o, n) -> {
			if (overlay != null)
				overlay.setUseAnnotationMask(n == ClassificationRegion.ANNOTATIONS_ONLY);
		});
		comboRegion.getSelectionModel().clearAndSelect(0);
		
		PaneTools.addGridRow(pane, row++, 0, 
				"Choose whether to apply the classifier to the whole image, or only regions containing annotations",
				labelRegion, comboRegion, comboRegion, comboRegion);

		
		// Live predict
		var btnAdvancedOptions = new Button("Advanced options");
		btnAdvancedOptions.setTooltip(new Tooltip("Advanced options to customize preprocessing and classifier behavior"));
		btnAdvancedOptions.setOnAction(e -> {
			if (showAdvancedOptions())
				updateClassifier();
		});
		
		var btnLive = new ToggleButton("Live prediction");
		btnLive.selectedProperty().bindBidirectional(livePrediction);
		btnLive.setTooltip(new Tooltip("Toggle whether to calculate classification 'live' while viewing the image"));
		livePrediction.addListener((v, o, n) -> {
			if (overlay == null) {
				if (n) {
					updateClassifier(n);				
					return;
				}
			} else {
				overlay.setLivePrediction(n);
			}
			if (featureOverlay != null)
				featureOverlay.setLivePrediction(n);
		});
				
		var panePredict = PaneTools.createColumnGridControls(btnAdvancedOptions, btnLive);
		pane.add(panePredict, 0, row++, pane.getColumnCount(), 1);
		
//		addGridRow(pane, row++, 0, btnPredict, btnPredict, btnPredict);

		var btnSave = new Button("Save");
		btnSave.setMaxWidth(Double.MAX_VALUE);
		btnSave.setOnAction(e -> saveAndApply());
		pane.add(btnSave, 0, row++, pane.getColumnCount(), 1);
		
		
		pieChart = new PieChart();
		
//		var hierarchy = viewer.getHierarchy();
//		Map<PathClass, List<PathObject>> map = hierarchy == null ? Collections.emptyMap() : PathClassificationLabellingHelper.getClassificationMap(hierarchy, false);
		
		pieChart.setLabelsVisible(false);
		pieChart.setLegendVisible(true);
		pieChart.setPrefSize(40, 40);
		pieChart.setMaxSize(100, 100);
		pieChart.setLegendSide(Side.RIGHT);
		GridPane.setVgrow(pieChart, Priority.ALWAYS);
		Tooltip.install(pieChart, new Tooltip("View training classes by proportion"));
		
		PaneTools.addGridRow(pane, row++, 0, 
				null,
//				"View information about the current classifier training",
				pieChart, pieChart, pieChart);
		
		
		// Label showing cursor location
		var labelCursor = new Label();
		labelCursor.textProperty().bindBidirectional(cursorLocation);
		labelCursor.setMaxWidth(Double.MAX_VALUE);
		labelCursor.setAlignment(Pos.CENTER);
		
		PaneTools.addGridRow(pane, row++, 0, 
				"Prediction for current cursor location",
				labelCursor, labelCursor, labelCursor);
		
		comboClassifier.getItems().addAll(
				OpenCVClassifiers.createStatModel(RTrees.class),
				OpenCVClassifiers.createStatModel(ANN_MLP.class),
				OpenCVClassifiers.createStatModel(LogisticRegression.class),
				OpenCVClassifiers.createStatModel(KNearest.class)
				);
		
		comboClassifier.getSelectionModel().clearAndSelect(0);
		
		PaneTools.setHGrowPriority(Priority.ALWAYS, comboResolutions, comboClassifier, comboFeatures);
		PaneTools.setFillWidth(Boolean.TRUE, comboResolutions, comboClassifier, comboFeatures);
		
		miniViewer = new MiniViewers.MiniViewerManager(viewer, 0);
		var viewerPane = miniViewer.getPane();
		Tooltip.install(viewerPane, new Tooltip("View image at classification resolution"));
		
		updateAvailableResolutions();	
		selectedResolution.addListener((v, o, n) -> {
			updateResolution(n);
			updateClassifier();
			updateFeatureOverlay();
		});
		if (!comboResolutions.getItems().isEmpty())
			comboResolutions.getSelectionModel().clearAndSelect(resolutions.size()/2);
		
		pane.setHgap(5);
		pane.setVgap(6);
		
//		var btnSavePrediction = new Button("Save prediction image");
//		btnSavePrediction.setMaxWidth(Double.MAX_VALUE);
//		btnSavePrediction.setOnAction(e -> saveAndApply());
//		pane.add(btnSavePrediction, 0, row++, pane.getColumnCount(), 1);

		var btnCreateObjects = new Button("Create objects");
		btnCreateObjects.setTooltip(new Tooltip("Create annotations or detections from pixel classification"));
		btnCreateObjects.disableProperty().bind(classificationComplete);
		btnCreateObjects.setOnAction(e -> {
			var server = getClassificationServerOrShowError();
			var imageData = viewer.getImageData();
			if (imageData != null && server != null)
				PixelClassifierTools.promptToCreateObjects(imageData, server);
		});
		
		var btnClassifyObjects = new Button("Classify detections");
		btnClassifyObjects.setTooltip(new Tooltip("Assign classifications to detection objects based on the corresponding pixel classification"));
		btnClassifyObjects.disableProperty().bind(classificationComplete);
		btnClassifyObjects.setOnAction(e -> classifyObjects());
		
		var panePostProcess = PaneTools.createColumnGridControls(btnCreateObjects, btnClassifyObjects);
				
		pane.add(panePostProcess, 0, row++, pane.getColumnCount(), 1);

		PaneTools.setMaxWidth(Double.MAX_VALUE, pane.getChildren().stream().filter(p -> p instanceof Region).toArray(Region[]::new));
		
		var viewerBorderPane = new BorderPane(viewerPane);
		
		comboDisplayFeatures.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> updateFeatureOverlay());
		comboDisplayFeatures.setMaxWidth(Double.MAX_VALUE);
		spinFeatureMin.setPrefWidth(100);
		spinFeatureMax.setPrefWidth(100);
		spinFeatureMin.valueProperty().addListener((v, o, n) -> updateFeatureDisplayRange());
		spinFeatureMax.valueProperty().addListener((v, o, n) -> updateFeatureDisplayRange());
		sliderFeatureOpacity.valueProperty().addListener((v, o, n) -> {
			if (featureOverlay != null) {
				featureOverlay.setOpacity(n.doubleValue());
			}
			if (overlay != null)
				overlay.setOpacity(n.doubleValue());
			viewer.repaint();
		});

		var btnFeatureAuto = new Button("Auto");
		btnFeatureAuto.setOnAction(e -> autoFeatureContrast());
		comboDisplayFeatures.getItems().setAll(DEFAULT_CLASSIFICATION_OVERLAY);
		comboDisplayFeatures.getSelectionModel().select(DEFAULT_CLASSIFICATION_OVERLAY);
		var featureDisableBinding = comboDisplayFeatures.valueProperty().isEqualTo(DEFAULT_CLASSIFICATION_OVERLAY).or(comboDisplayFeatures.valueProperty().isNull());
		btnFeatureAuto.disableProperty().bind(featureDisableBinding);
		btnFeatureAuto.setMaxHeight(Double.MAX_VALUE);
		spinFeatureMin.disableProperty().bind(featureDisableBinding);
		spinFeatureMin.setEditable(true);
		GuiTools.restrictSpinnerInputToNumber(spinFeatureMin, true);
		spinFeatureMax.disableProperty().bind(featureDisableBinding);
		spinFeatureMax.setEditable(true);
		GuiTools.restrictSpinnerInputToNumber(spinFeatureMax, true);
		var paneFeatures = new GridPane();
		comboDisplayFeatures.setTooltip(new Tooltip("Choose classification result or feature overlay to display (Warning: This requires a lot of memory & computation!)"));
		spinFeatureMin.setTooltip(new Tooltip("Min display value for feature overlay"));
		spinFeatureMax.setTooltip(new Tooltip("Max display value for feature overlay"));
		sliderFeatureOpacity.setTooltip(new Tooltip("Adjust classification/feature overlay opacity"));
		
		PaneTools.addGridRow(paneFeatures, 0, 0, null,
				comboDisplayFeatures, comboDisplayFeatures, comboDisplayFeatures, comboDisplayFeatures);
		PaneTools.addGridRow(paneFeatures, 1, 0, null,
				sliderFeatureOpacity, spinFeatureMin, spinFeatureMax, btnFeatureAuto);

		
		var factory = new Callback<ListView<String>, ListCell<String>>() {

			@Override
			public ListCell<String> call(ListView<String> param) {
				var listCell = new ListCell<String>() {
					@Override
					public void updateItem(String value, boolean empty) {
						super.updateItem(value, empty);
						if (value == null || empty)
							setText(null);
						else
							setText(value);
					}
				};
				listCell.setTextOverrun(OverrunStyle.ELLIPSIS);
				return listCell;
			}
		};
		comboDisplayFeatures.setCellFactory(factory);
		comboDisplayFeatures.setButtonCell(factory.call(null));
		
		PaneTools.setMaxWidth(Double.MAX_VALUE, comboDisplayFeatures, sliderFeatureOpacity);
		PaneTools.setFillWidth(Boolean.TRUE, comboDisplayFeatures, sliderFeatureOpacity);
		PaneTools.setHGrowPriority(Priority.ALWAYS, comboDisplayFeatures, sliderFeatureOpacity);
		paneFeatures.setHgap(5);
		paneFeatures.setVgap(5);
		paneFeatures.setPadding(new Insets(5));
		paneFeatures.prefWidthProperty().bind(viewerBorderPane.prefWidthProperty());
		viewerBorderPane.setBottom(paneFeatures);
		
		var splitPane = new BorderPane(viewerBorderPane);
		splitPane.setLeft(pane);
		pane.setPrefWidth(400);
		
		var fullPane = splitPane;//new StackPane(splitPane);
		
		pane.setPadding(new Insets(5));
		
		stage = new Stage();
		stage.setScene(new Scene(fullPane));
		
		stage.setMinHeight(400);
		stage.setMinWidth(500);

		stage.initOwner(QuPathGUI.getInstance().getStage());
		
		stage.getScene().getRoot().disableProperty().bind(
				QuPathGUI.getInstance().viewerProperty().isNotEqualTo(viewer)
				);
		
		updateTitle();
		
		updateFeatureCalculator();
		
		PaneTools.setMinWidth(
				Region.USE_PREF_SIZE,
				PaneTools.getContentsOfType(stage.getScene().getRoot(), Region.class, true).toArray(Region[]::new));
		
		stage.show();
		stage.setOnCloseRequest(e -> destroy());
		
		viewer.getView().addEventFilter(MouseEvent.MOUSE_MOVED, mouseListener);
		
		viewer.getImageDataProperty().addListener(imageDataListener);
		if (viewer.getImageData() != null)
			viewer.getImageData().getHierarchy().addPathObjectListener(hierarchyListener);
		
	}
	
	
	
	/**
	 * Add to the list of default feature calculator builders that will be available when 
	 * this pane is opened.
	 * <p>
	 * This provides a mechanism to install additional feature calculators.
	 * <p>
	 * Note that the builder will only be added if it is not already present.
	 * @param builder the builder to be installed
	 * 
	 * @return true if the builder was added, false otherwise.
	 */
	public synchronized static boolean installDefaultFeatureClassificationBuilder(ImageDataTransformerBuilder builder) {
		if (!defaultFeatureCalculatorBuilders.contains(builder)) {
			defaultFeatureCalculatorBuilders.add(builder);
			return true;
		}
		return false;
	}
	
	
	private void updateTitle() {
		if (stage == null)
			return;
//		var imageData = viewer.getImageData();
//		if (imageData == null)
			stage.setTitle("Pixel classifier");
//		else
//			stage.setTitle("Pixel classifier (" + imageData.getServer().getDisplayedImageName() + ")");
	}
	
	private MouseListener mouseListener = new MouseListener();
	
	private PixelClassifierTraining helper;

	private FeatureNormalization normalization = new FeatureNormalization();
	private ImageOp preprocessingOp = null;

		
	private void updateAvailableResolutions() {
		var imageData = viewer.getImageData();
		if (imageData == null) {
			resolutions.clear();
			return;
		}
		var selected = selectedResolution.get();
		resolutions.setAll(ClassificationResolution.getDefaultResolutions(imageData, selected));
		comboResolutions.getSelectionModel().select(selected);
	}
	
	
	private void updateFeatureCalculator() {
		var cal = getSelectedResolution();
		helper.setFeatureOp(selectedFeatureCalculatorBuilder.get().build(viewer.getImageData(), cal));
		var featureServer = helper.getFeatureServer();
		if (featureServer == null) {
			comboDisplayFeatures.getItems().setAll(DEFAULT_CLASSIFICATION_OVERLAY);
		} else {
			List<String> featureNames = new ArrayList<>();
			featureNames.add(DEFAULT_CLASSIFICATION_OVERLAY);
			for (var channel : featureServer.getMetadata().getChannels())
				featureNames.add(channel.getName());
			comboDisplayFeatures.getItems().setAll(featureNames);
		}
		comboDisplayFeatures.getSelectionModel().select(DEFAULT_CLASSIFICATION_OVERLAY);
		updateClassifier();
	}
	
	private PixelClassificationOverlay featureOverlay = null;
	private FeatureRenderer featureRenderer;
	
	private void autoFeatureContrast() {
		var selectedChannel = featureRenderer == null ? null : featureRenderer.getSelectedChannel();
		if (selectedChannel != null) {
			featureRenderer.autoSetDisplayRange();
			double min = (double)selectedChannel.getMinDisplay();
			double max = (double)selectedChannel.getMaxDisplay();
			spinFeatureMin.getValueFactory().setValue(min);
			spinFeatureMax.getValueFactory().setValue(max);
		}
	}
	
	private void updateFeatureOverlay() {
		if (featureOverlay != null) {
			featureOverlay.setVisible(false);
			featureOverlay.stop();
			if (viewer.getCustomPixelLayerOverlay() == featureOverlay)
				viewer.resetCustomPixelLayerOverlay();
			featureOverlay = null;
		}
		var featureServer = helper.getFeatureServer();
		String featureName = comboDisplayFeatures.getSelectionModel().getSelectedItem();
		if (DEFAULT_CLASSIFICATION_OVERLAY.equals(featureName)) {
			if (overlay != null)
				overlay.setImageData(viewer.getImageData());
			viewer.setCustomPixelLayerOverlay(overlay);
			return;
		}
		int channel = -1;
		if (featureServer != null && featureName != null) {
			for (int c = 0; c < featureServer.nChannels(); c++) {
				if (featureName.equals(featureServer.getChannel(c).getName())) {
					channel = c;
					break;
				}
			}
			if (channel >= 0) {
				featureRenderer.setChannel(featureServer, channel, spinFeatureMin.getValue(), spinFeatureMax.getValue());
				featureOverlay = PixelClassificationOverlay.createFeatureDisplayOverlay(viewer, featureServer, featureRenderer);
				((PixelClassificationOverlay)featureOverlay).setLivePrediction(true);
				featureOverlay.setOpacity(sliderFeatureOpacity.getValue());
				featureOverlay.setLivePrediction(livePrediction.get());
				autoFeatureContrast();
			}
		}
		if (featureOverlay != null)
			viewer.setCustomPixelLayerOverlay(featureOverlay);
	}
	
	
	private void ensureOverlaySet() {
		updateFeatureOverlay();
	}
	
	
	private void updateFeatureDisplayRange() {
		if (featureRenderer == null)
			return;
		featureRenderer.setRange(spinFeatureMin.getValue(), spinFeatureMax.getValue());
		viewer.repaint();
	}
	
	private void updateClassifier() {
		updateClassifier(livePrediction.get());
	}
	
	
	
	private void updateClassifier(boolean doClassification) {
				
		helper.setImageData(viewer.getImageData());
		
		if (doClassification)
			doClassification();
		else
			replaceOverlay(null);
	}
	
	private boolean reweightSamples = false;
	private int maxSamples = 100_000;
	private int rngSeed = 100;
	
	private boolean showAdvancedOptions() {
		
		var existingStrategy = helper.getBoundaryStrategy();
		
		List<BoundaryStrategy> boundaryStrategies = new ArrayList<>();
		boundaryStrategies.add(BoundaryStrategy.getSkipBoundaryStrategy());
		boundaryStrategies.add(BoundaryStrategy.getDerivedBoundaryStrategy(1));
		for (var pathClass : QuPathGUI.getInstance().getAvailablePathClasses())
			boundaryStrategies.add(BoundaryStrategy.getClassifyBoundaryStrategy(pathClass, 1));
		
		String PCA_NONE = "No feature reduction";
		String PCA_BASIC = "Do PCA projection";
		String PCA_NORM = "Do PCA projection + normalize output";
		
		String pcaChoice = PCA_NONE;
		if (normalization.getPCARetainedVariance() > 0) {
			if (normalization.doPCANormalize())
				pcaChoice = PCA_NORM;
			else
				pcaChoice = PCA_BASIC;
		}
		
		
		var params = new ParameterList()
				.addTitleParameter("Training data")
				.addIntParameter("maxSamples", "Maximum samples", maxSamples, null, "Maximum number of training samples")
				.addIntParameter("rngSeed", "RNG seed", rngSeed, null, "Seed for the random number generator used when training (not relevant to all classifiers)")
				.addBooleanParameter("reweightSamples", "Reweight samples", reweightSamples, "Weight training samples according to frequency")
				.addTitleParameter("Preprocessing")
				.addChoiceParameter("normalization", "Feature normalization", normalization.getNormalization(),
						Arrays.asList(Normalization.values()), "Method to normalize features")
				.addChoiceParameter("featureReduction", "Feature reduction", pcaChoice, List.of(PCA_NONE, PCA_BASIC, PCA_NORM), 
						"Use Principal Component Analysis for feature reduction (must also specify retained variance)")
				.addDoubleParameter("pcaRetainedVariance", "PCA retained variance", normalization.getPCARetainedVariance(), "",
						"Retained variance if applying Principal Component Analysis for dimensionality reduction. Should be between 0 and 1; if <= 0 PCA will not be applied.")
				.addTitleParameter("Annotation boundaries")
				.addChoiceParameter("boundaryStrategy", "Boundary strategy", helper.getBoundaryStrategy(),
						boundaryStrategies,
						"Choose how annotation boundaries should influence classifier training")
				.addDoubleParameter("boundaryThickness", "Boundary thickness", existingStrategy.getBoundaryThickness(), "pixels",
						"Set the boundary thickness whenever annotation boundaries are trained separately");
		
		if (!Dialogs.showParameterDialog("Advanced options", params))
			return false;
		
		reweightSamples = params.getBooleanParameterValue("reweightSamples");
		maxSamples = params.getIntParameterValue("maxSamples");
		rngSeed = params.getIntParameterValue("rngSeed");
		
		pcaChoice = (String)params.getChoiceParameterValue("featureReduction");
		boolean pcaNormalize = PCA_NORM.equals(pcaChoice);
		double pcaRetainedVariance = PCA_NONE.equals(pcaChoice) ? 0 : params.getDoubleParameterValue("pcaRetainedVariance");
		
		normalization.setNormalization((Normalization)params.getChoiceParameterValue("normalization"));
		normalization.setPCARetainedVariance(pcaRetainedVariance);
		normalization.setPCANormalize(pcaNormalize);
		
		var strategy = (BoundaryStrategy)params.getChoiceParameterValue("boundaryStrategy");
		strategy = BoundaryStrategy.setThickness(strategy, params.getDoubleParameterValue("boundaryThickness"));
		helper.setBoundaryStrategy(strategy);
		
		return true;
	}
	
	
	
	private void doClassification() {
//		if (helper == null || helper.getFeatureServer() == null) {
////			updateFeatureCalculator();
////			updateClassifier();
//			if (helper == null) {
//				logger.error("No pixel classifier helper available!");
//				return;
//			}
//		}
		if (helper.getFeatureServer() == null) {
			Dialogs.showErrorNotification("Pixel classifier", "No feature calculator available!");
			return;			
		}
		
		var model = selectedClassifier.get();
		if (model == null) {
			Dialogs.showErrorNotification("Pixel classifier", "No classifier selected!");
			return;
		}

		ClassifierTrainingData trainingData;
		try {
			trainingData = helper.createTrainingData();
		} catch (Exception e) {
			logger.error("Error when updating training data", e);
			return;
		}
		 if (trainingData == null) {
			 resetPieChart();
			 return;
		 }

		 // TODO: Optionally limit the number of training samples we use
		 //	     		var trainData = classifier.createTrainData(matFeatures, matTargets);

		 // Ensure we seed the RNG for reproducibility
		 opencv_core.setRNGSeed(rngSeed);
		 
		 // TODO: Prevent training K nearest neighbor with a huge number of samples (very slow!)
		 var actualMaxSamples = this.maxSamples;
		 
		 var trainData = trainingData.getTrainData();
		 if (actualMaxSamples > 0 && trainData.getNTrainSamples() > actualMaxSamples)
			 trainData.setTrainTestSplit(actualMaxSamples, true);
		 else
			 trainData.shuffleTrainTest();

//		 System.err.println("Train: " + trainData.getTrainResponses());
//		 System.err.println("Test: " + trainData.getTestResponses());
		 
		 // Apply normalization, if we need to
		 FeaturePreprocessor preprocessor = normalization.build(trainData.getTrainSamples(), false);
		 if (preprocessor.doesSomething()) {
			 preprocessingOp = ImageOps.ML.preprocessor(preprocessor);
		 } else
			 preprocessingOp = null;
		 
		 var labels = trainingData.getLabelMap();
		 var targets = trainData.getTrainNormCatResponses();
		 IntBuffer buffer = targets.createBuffer();
		 int n = (int)targets.total();
		 var rawCounts = new int[labels.size()];
		 for (int i = 0; i < n; i++) {
			 rawCounts[buffer.get(i)] += 1;
		 }
		 Map<PathClass, Integer> counts = new LinkedHashMap<>();
		 for (var entry : labels.entrySet()) {
			 counts.put(entry.getKey(), rawCounts[entry.getValue()]);
		 }
		 updatePieChart(counts);
		 
		 Mat weights = null;
		 if (reweightSamples) {
			 weights = new Mat(n, 1, opencv_core.CV_32FC1);
			 FloatIndexer bufferWeights = weights.createIndexer();
			 float[] weightArray = new float[rawCounts.length];
			 for (int i = 0; i < weightArray.length; i++) {
				 int c = rawCounts[i];
//				 weightArray[i] = c == 0 ? 1 : (float)1.f/c;
				 weightArray[i] = c == 0 ? 1 : (float)n/c;
			 }
			 for (int i = 0; i < n; i++) {
				 int label = buffer.get(i);
				 bufferWeights.put(i, weightArray[label]);
			 }
			 bufferWeights.release();
		 }
		 
		 // Create TrainData in an appropriate format (e.g. labels or one-hot encoding)
		 var trainSamples = trainData.getTrainSamples();
		 preprocessor.apply(trainSamples, false);
		 trainData = model.createTrainData(trainSamples, trainData.getTrainResponses(), weights, false);
		 model.train(trainData);
		 
		 // Calculate accuracy using whatever we can, as a rough guide to progress
		 var test = trainData.getTestSamples();
		 String testSet = "HELD-OUT TRAINING SET";
		 if (test.empty()) {
			 test = trainSamples;
			 testSet = "TRAINING SET";
		 } else {
			 preprocessor.apply(test, false);
			 buffer = trainData.getTestNormCatResponses().createBuffer();
		 }
		 var testResults = new Mat();
		 model.predict(test, testResults, null);
		 IntBuffer bufferResults = testResults.createBuffer();
		 int nTest = (int)testResults.rows();
		 int nCorrect = 0;
		 for (int i = 0; i < nTest; i++) {
			 if (bufferResults.get(i) == buffer.get(i))
				 nCorrect++;
		 }
		 logger.info("Current accuracy on the {}: {} %", testSet, GeneralTools.formatNumber(nCorrect*100.0/n, 1));

		 if (model instanceof RTreesClassifier) {
			 var trees = (RTreesClassifier)model;
			 if (trees.hasFeatureImportance())
				 logVariableImportance(trees,
						 helper.getFeatureServer().getMetadata().getChannels().stream()
						 .map(c -> c.getName()).collect(Collectors.toList()));
		 }
		 
		 trainData.close();

		 
		 var featureCalculator = helper.getFeatureOp();
		 if (preprocessingOp != null)
			 featureCalculator = featureCalculator.appendOps(preprocessingOp);
		 
		 // TODO: CHECK IF INPut SIZE SHOULD BE DEFINED
		 int inputWidth = 512;
		 int inputHeight = 512;
//		 int inputWidth = featureCalculator.getInputSize().getWidth();
//		 int inputHeight = featureCalculator.getInputSize().getHeight();
		 var cal = helper.getResolution();
		 var channelType = ImageServerMetadata.ChannelType.CLASSIFICATION;
		 if (model.supportsProbabilities()) {
			 channelType = selectedOutputType.get();
		 }
		 
		 // Channels are needed for probability output (and work for classification as well)
		 var labels2 = new TreeMap<Integer, PathClass>();
		 for (var entry : labels.entrySet()) {
			 var previous = labels2.put(entry.getValue(), entry.getKey());
			 if (previous != null)
				 logger.warn("Duplicate label found! {} matches with {} and {}, only the latter be used", entry.getValue(), previous, entry.getKey());
		 }
		 var channels = PathClassifierTools.classificationLabelsToChannels(labels2, true);
		 
		 PixelClassifierMetadata metadata = new PixelClassifierMetadata.Builder()
				 .inputResolution(cal)
				 .inputShape(inputWidth, inputHeight)
				 .setChannelType(channelType)
//				 .classificationLabels(labels2)
				 .outputChannels(channels)
				 .build();

		 var classifier = PixelClassifiers.createClassifier(model, featureCalculator, metadata, true);

		 var overlay = PixelClassificationOverlay.createPixelClassificationOverlay(viewer, classifier);
		 replaceOverlay(overlay);
	}
	
	
	
	private void resetPieChart() {
		updatePieChart(Collections.emptyMap());
	}
	
	private void updatePieChart(Map<PathClass, Integer> counts) {
		if (!Platform.isFxApplicationThread()) {
			Platform.runLater(() -> updatePieChart(counts));
			return;
		}
		ChartTools.setPieChartData(pieChart, counts, PathClass::toString, p -> ColorToolsFX.getCachedColor(p.getColor()), true, !counts.isEmpty());
	}
	
	
	static boolean logVariableImportance(final RTreesClassifier trees, final List<String> features) {
		var importance = trees.getFeatureImportance();
		if (importance == null)
			return false;
		try {
			var sorted = IntStream.range(0, importance.length)
					.boxed()
					.sorted((a, b) -> -Double.compare(importance[a], importance[b]))
					.mapToInt(i -> i).toArray();
			
			if (sorted.length != features.size())
				return false;
			
			var sb = new StringBuilder("Variable importance:");
			for (int ind : sorted) {
				sb.append("\n");
				sb.append(String.format("%.4f \t %s", importance[ind], features.get(ind)));
			}
			logger.info(sb.toString());
			return true;
		} catch (Exception e) {
			logger.debug("Error logging feature importance: {}", e.getLocalizedMessage());
			return false;
		}
	}
	
	private PixelClassificationOverlay overlay;

	/**
	 * Replace the overlay - making sure to do this on the application thread
	 *
	 * @param newOverlay
	 */
	private void replaceOverlay(PixelClassificationOverlay newOverlay) {
		if (!Platform.isFxApplicationThread()) {
			Platform.runLater(() -> replaceOverlay(newOverlay));
			return;
		}
		if (overlay != null) {
			overlay.stop();
		}
		overlay = newOverlay;
		if (overlay != null) {
			overlay.setUseAnnotationMask(selectedRegion.get() == ClassificationRegion.ANNOTATIONS_ONLY);
			overlay.setLivePrediction(livePrediction.get());
			overlay.setOpacity(sliderFeatureOpacity.getValue());
		}
		ensureOverlaySet();
	}
		
	



	private void destroy() {
		if (overlay != null) {
			var imageData = viewer.getImageData();
			if (imageData != null && PixelClassificationImageServer.getPixelLayer(imageData) == overlay.getPixelClassificationServer())
				PixelClassificationImageServer.setPixelLayer(imageData, null);
			overlay.stop();
			viewer.resetCustomPixelLayerOverlay();
			overlay = null;
		}
		if (featureOverlay != null) {
			viewer.getCustomOverlayLayers().remove(featureOverlay);
			featureOverlay = null;
		}

		viewer.getView().removeEventFilter(MouseEvent.MOUSE_MOVED, mouseListener);
		viewer.getImageDataProperty().removeListener(imageDataListener);
		var hierarchy = viewer.getHierarchy();
		if (hierarchy != null)
			hierarchy.removePathObjectListener(hierarchyListener);
//		setImageData(viewer, viewer.getImageData(), null);
		if (helper != null)
			helper.setImageData(null);
		if (stage != null && stage.isShowing())
			stage.close();
	}
	
	
	
	private boolean saveAndApply() {
		logger.debug("Saving & applying classifier");
		updateClassifier(true);
		
		PixelClassificationImageServer server = null;
		if (overlay != null)
			server = getClassificationServerOrShowError();
		if (server == null) {
			Dialogs.showErrorMessage("Pixel classifier", "Nothing to save - please train a classifier first!");
			return false;
		}
		
		var project = QuPathGUI.getInstance().getProject();
		if (project == null) {
			Dialogs.showErrorMessage("Pixel classifier", "Saving pixel classification requires a project!");
			return false;
		}
			
		try {
			var imageData = viewer.getImageData();
			return PixelClassifierTools.saveAndApply(project, imageData, server.getClassifier());
//			var resultServer = saveAndApply(project, imageData, server.getClassifier());
//			if (resultServer != null) {
//				PixelClassificationImageServer.setPixelLayer(imageData, resultServer);
//				wasApplied = true;
//				replaceOverlay(null);
//				return true;
//			}
		} catch (Exception e) {
			Dialogs.showErrorMessage("Pixel classifier", e);
		}
		return false;
	}
	
	
	private PixelClassificationImageServer getClassificationServerOrShowError() {
		var hierarchy = viewer.getHierarchy();
		if (hierarchy == null)
			return null;
		var server = overlay == null ? null : overlay.getPixelClassificationServer();
		if (server == null || !(server instanceof PixelClassificationImageServer)) {
			Dialogs.showErrorMessage("Pixel classifier", "No classifier available!");
			return null;
		}
		return (PixelClassificationImageServer)server;
	}
	
	
	private boolean classifyObjects() {
		var hierarchy = viewer.getHierarchy();
		if (hierarchy == null)
			return false;
		var server = getClassificationServerOrShowError();
		if (server == null) {
			return false;
		}
		PixelClassifierTools.classifyObjectsByCentroid(server, hierarchy.getDetectionObjects(), true);
		return true;
	}
	
	
	
	
	
	private boolean editClassifierParameters() {
		var model = selectedClassifier.get();
		if (model == null) {
			Dialogs.showErrorMessage("Edit parameters", "No classifier selected!");
			return false;
		}
		Dialogs.showParameterDialog("Edit parameters", model.getParameterList());
		updateClassifier();
		return true;
	}
	
	
	private boolean showOutput() {
		if (overlay == null) {
			Dialogs.showErrorMessage("Show output", "No pixel classifier has been trained yet!");
			return false;
		}
		var server = overlay.getPixelClassificationServer();
		if (server == null)
			return false;
		var selected = viewer.getSelectedObject();
		var roi = selected == null ? null : selected.getROI();
		double downsample = server.getDownsampleForResolution(0);
		RegionRequest request;
		if (roi == null) {
			request = RegionRequest.createInstance(
					server.getPath(), downsample, 
					0, 0, server.getWidth(), server.getHeight(), viewer.getZPosition(), viewer.getTPosition());			
		} else {
			request = RegionRequest.createInstance(server.getPath(), downsample, selected.getROI());
		}
		long estimatedPixels = (long)Math.ceil(request.getWidth()/request.getDownsample()) * (long)Math.ceil(request.getHeight()/request.getDownsample());
		double estimatedMB = (estimatedPixels * server.nChannels() * (server.getPixelType().getBytesPerPixel())) / (1024.0 * 1024.0);
		if (estimatedPixels >= Integer.MAX_VALUE - 16) {
			Dialogs.showErrorMessage("Extract output", "Requested region is too big! Try selecting a smaller region.");
			return false;
		} else if (estimatedMB >= 200.0) {
			if (!Dialogs.showConfirmDialog("Extract output",
					String.format("Extracting this region will require approximately %.1f MB - are you sure you want to try this?", estimatedMB)))
				return false;
		}
		
		try {
//			var imp = IJExtension.extractROI(server, selected, request, true, null).getImage();
			var pathImage = IJTools.convertToImagePlus(
					server,
					request);
			var imp = pathImage.getImage();
			if (imp instanceof CompositeImage && server.getMetadata().getChannelType() != ChannelType.CLASSIFICATION)
				((CompositeImage)imp).setDisplayMode(CompositeImage.GRAYSCALE);
			if (roi != null && !(roi instanceof RectangleROI)) {
				imp.setRoi(IJTools.convertToIJRoi(roi, pathImage));
			}
			IJExtension.getImageJInstance();
			imp.show();
			return true;
		} catch (IOException e) {
			logger.error("Error showing output", e);
		}
		return false;
	}
	
	
	private boolean showFeatures() {
		ImageData<BufferedImage> imageData = viewer.getImageData();
		double cx = viewer.getCenterPixelX();
		double cy = viewer.getCenterPixelY();
		if (imageData == null)
			return false;

		try {
			// Create a new FeatureServer if we need one
			ImageServer<BufferedImage> featureServer;
			
			var op = helper.getFeatureOp();
			if (preprocessingOp != null)
				op = op.appendOps(preprocessingOp);
			featureServer = ImageOps.buildServer(imageData, op, helper.getResolution());
			
//			boolean tempFeatureServer = false;
//			if (helper.getImageData() == imageData) {
//				featureServer = helper.getFeatureServer();
//			} else {
//				tempFeatureServer = true;
//				featureServer = ImageOps.buildServer(imageData, helper.getFeatureOp(), helper.getResolution());
//			}
			double downsample = featureServer.getDownsampleForResolution(0);
			int tw = (int)(featureServer.getMetadata().getPreferredTileWidth() * downsample);
			int th = (int)(featureServer.getMetadata().getPreferredTileHeight() * downsample);
			int x = (int)GeneralTools.clipValue(cx - tw/2, 0, featureServer.getWidth() - tw);
			int y = (int)GeneralTools.clipValue(cy - th/2, 0, featureServer.getHeight() - th);
			var request = RegionRequest.createInstance(
					featureServer.getPath(),
					downsample,
					x, y, tw, th, viewer.getZPosition(), viewer.getTPosition());
//			var tile = featureServer.getTileRequestManager().getTileRequest(
//					0,
//					(int)cx,
//					(int)cy,
//					viewer.getZPosition(), viewer.getTPosition());
//			if (tile == null) {
//				DisplayHelpers.showErrorMessage("Show features", "To file found - center the image within the viewer, then try again");
//				return false;
//			}
			
			var imp = IJTools.convertToImagePlus(featureServer, request).getImage();

			CompositeImage impComp = new CompositeImage(imp, CompositeImage.GRAYSCALE);
			impComp.setDimensions(imp.getStackSize(), 1, 1);
			for (int s = 1; s <= imp.getStackSize(); s++) {
				impComp.setPosition(s);
				impComp.resetDisplayRange();
//				impComp.getStack().setSliceLabel(feature.getName(), s++);
			}
			impComp.setPosition(1);
			IJExtension.getImageJInstance();
			impComp.show();
			
//			if (tempFeatureServer)
				featureServer.close();
			return true;
		} catch (Exception e) {
			logger.error("Error calculating features", e);
		}
		return false;
	}
	
	private boolean addResolution() {
		ImageServer<BufferedImage> server = viewer.getServer();
		if (server == null) {
			Dialogs.showNoImageError("Add resolution");
			return false;
		}
		String units = null;
		Double pixelSize = null;
		PixelCalibration cal = server.getPixelCalibration();
		if (cal.hasPixelSizeMicrons()) {
			pixelSize = Dialogs.showInputDialog("Add resolution", "Enter requested pixel size in " + GeneralTools.micrometerSymbol(), 1.0);
			units = PixelCalibration.MICROMETER;
		} else {
			pixelSize = Dialogs.showInputDialog("Add resolution", "Enter requested downsample factor", 1.0);
		}
		
		if (pixelSize == null)
			return false;
		
		ClassificationResolution res;
		if (PixelCalibration.MICROMETER.equals(units)) {
			double scale = pixelSize / cal.getAveragedPixelSizeMicrons();
			res = new ClassificationResolution("Custom", cal.createScaledInstance(scale, scale, 1));
		} else
			res = new ClassificationResolution("Custom", cal.createScaledInstance(pixelSize, pixelSize, 1));

		List<ClassificationResolution> temp = new ArrayList<>(resolutions);
		temp.add(res);
		Collections.sort(temp, Comparator.comparingDouble((ClassificationResolution w) -> w.cal.getAveragedPixelSize().doubleValue()));
		resolutions.setAll(temp);
		comboResolutions.getSelectionModel().select(res);
		
		return true;
	}	
	
	
	private PixelCalibration getSelectedResolution() {
		return selectedResolution.get().cal;
	}
	
	
	private void updateResolution(ClassificationResolution resolution) {
		ImageServer<BufferedImage> server = null;
		if (viewer != null)
			server = viewer.getServer();
		if (server == null || miniViewer == null || resolution == null)
			return;
		Tooltip.install(miniViewer.getPane(), new Tooltip("Classification resolution: \n" + resolution));
		helper.setResolution(resolution.cal);
		miniViewer.setDownsample(resolution.cal.getAveragedPixelSize().doubleValue()  / server.getPixelCalibration().getAveragedPixelSize().doubleValue());
	}
	
	
	
	
	
	class MouseListener implements EventHandler<MouseEvent> {

		@Override
		public void handle(MouseEvent event) {
			if (overlay == null)
				return;
			var p = viewer.componentPointToImagePoint(event.getX(), event.getY(), null, false);
			var server = overlay.getPixelClassificationServer();
			String results = null;
			if (server != null)
				results = getResultsString(server, p.getX(), p.getY(), viewer.getZPosition(), viewer.getTPosition());
			if (results == null)
				cursorLocation.set("");
			else
				cursorLocation.set(results);
		}
		
	}
	
	
	/**
	 * Get a String summarizing the pixel classification values at a specified pixel location.
	 * 
	 * @param classifierServer
	 * @param x
	 * @param y
	 * @param z
	 * @param t
	 * @return
	 */
	private static String getResultsString(ImageServer<BufferedImage> classifierServer, double x, double y, int z, int t) {
    	if (classifierServer == null)
    		return null;
    	
    	int level = 0;
    	var tile = classifierServer.getTileRequestManager().getTileRequest(level, (int)Math.round(x), (int)Math.round(y), z, t);
    	if (tile == null)
    		return null;
    	var img = classifierServer.getCachedTile(tile);
    	if (img == null)
    		return null;

    	int xx = (int)Math.floor((x - tile.getImageX()) / tile.getDownsample());
    	int yy = (int)Math.floor((y - tile.getImageY()) / tile.getDownsample());
    	if (xx < 0 || yy < 0 || xx >= img.getWidth() || yy >= img.getHeight())
    		return null;
    	
//    	String coords = GeneralTools.formatNumber(x, 1) + "," + GeneralTools.formatNumber(y, 1);
    	
    	if (classifierServer.getMetadata().getChannelType() == ImageServerMetadata.ChannelType.CLASSIFICATION) {
        	var classificationLabels = classifierServer.getMetadata().getClassificationLabels();
        	int sample = img.getRaster().getSample(xx, yy, 0); 		
        	return String.format("Classification: %s", classificationLabels.get(sample).getName());
//        	return String.format("Classification (%s):\n%s", coords, channels.get(sample).getName());
    	} else {
        	var channels = classifierServer.getMetadata().getChannels();
    		String[] array = new String[channels.size()];
    		for (int c = 0; c < channels.size(); c++) {
    			float sample = img.getRaster().getSampleFloat(xx, yy, c);
    			if (img.getRaster().getDataBuffer().getDataType() == DataBuffer.TYPE_BYTE)
    				sample /= 255f;
    			array[c] = channels.get(c).getName() + ": " + GeneralTools.formatNumber(sample, 2);
    		}
        	return String.format("Prediction: %s", String.join(", ", array));
    	}
    }
	
	
	class HierarchyListener implements PathObjectHierarchyListener {

		@Override
		public void hierarchyChanged(PathObjectHierarchyEvent event) {
			if (!event.isChanging() && !event.isObjectMeasurementEvent() && (event.isStructureChangeEvent() || event.isObjectClassificationEvent() || !event.getChangedObjects().isEmpty())) {
				if (event.isObjectClassificationEvent()  || event.getChangedObjects().stream().anyMatch(p -> p.getPathClass() != null)) {
					if (event.getChangedObjects().stream().anyMatch(p -> p.isAnnotation()))
						updateClassifier();
				}
			}
		}
		
	}


}
