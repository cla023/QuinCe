var FLAG_GOOD = 2;
var FLAG_ASSUMED_GOOD = -2;
var FLAG_QUESTIONABLE = 3;
var FLAG_BAD = 4;
var FLAG_FATAL = 44;
var FLAG_NEEDS_FLAG = -10;
var FLAG_FLUSHING = -100;
var FLAG_IGNORED = -1002;

var SELECT_ACTION = 1;
var DESELECT_ACTION = 0;
var SELECTION_POINT = -100;

var PLOT_POINT_SIZE = 2;
var PLOT_HIGHLIGHT_SIZE = 5;
var PLOT_FLAG_SIZE = 8;

var PLOT_X_AXIS_INDEX = 0;
var PLOT_MEASUREMENT_ID_INDEX = 1;
var PLOT_MANUAL_FLAG_INDEX = 2;
var PLOT_FIRST_Y_INDEX = 3;

var MAP_MEASUREMENT_ID_INDEX = 2;
var MAP_MANUAL_FLAG_INDEX = 3;

// Colors used to highlight points in plot, depending on quality flag or
// selection
var HIGHLIGHT_COLORS = {};
HIGHLIGHT_COLORS[FLAG_BAD] = 'rgba(255, 0, 0, 1)';
HIGHLIGHT_COLORS[FLAG_FATAL] = 'rgba(255, 0, 0, 1)';
HIGHLIGHT_COLORS[FLAG_QUESTIONABLE] = 'rgba(216, 177, 0, 1)';
HIGHLIGHT_COLORS[FLAG_NEEDS_FLAG] = 'rgba(129, 127, 255, 1)';
HIGHLIGHT_COLORS[FLAG_IGNORED] = 'rgba(225, 225, 225, 1)';
HIGHLIGHT_COLORS[SELECTION_POINT] = 'rgba(255, 221, 0, 1)';

var BASE_GRAPH_OPTIONS = {
  drawPoints: true,
  strokeWidth: 0.0,
  labelsUTC: true,
  labelsSeparateLine: true,
  digitsAfterDecimal: 2,
  animatedZooms: true,
  pointSize: PLOT_POINT_SIZE,
  highlightCircleSize: PLOT_HIGHLIGHT_SIZE,
  selectMode: 'euclidian',
  axes: {
    x: {
      drawGrid: false
    },
    y: {
      drawGrid: true,
      gridLinePattern: [1, 3],
      gridLineColor: 'rbg(200, 200, 200)',
    }
  },
  xRangePad: 10,
  yRangePad: 10,
  clickCallback: function(e, x, points) {
    scrollToTableRow(getRowId(e, x, points));
  }
};

var VARIABLES_DIALOG_ENTRY_HEIGHT = 35;

//Controller for input updates
var updatingButtons = false;
var variablesPlotIndex = 1;


//The two plots
var plot1 = null;
var plot2 = null;

//Map variables
var map1 = null;
var map2 = null;

var map1ColorScale = new ColorScale([[0,'#FFFFD4'],[0.25,'#FED98E'],[0.5,'#FE9929'],[0.75,'#D95F0E'],[1,'#993404']]);
map1ColorScale.setFont('Noto Sans', 11);
var map1ScaleVisible = true;

var map2ColorScale = new ColorScale([[0,'#FFFFD4'],[0.25,'#FED98E'],[0.5,'#FE9929'],[0.75,'#D95F0E'],[1,'#993404']]);
map2ColorScale.setFont('Noto Sans', 11);
var map2ScaleVisible = true;

var map1DataLayer = null;
var map2DataLayer = null;

var map1Extent = null;
var map2Extent = null;

var redrawMap = true;

var scaleOptions = {
    outliers: 'b',
    outlierSize: 5,
    decimalPlaces: 3
};

var mapSource = new ol.source.Stamen({
  layer: "terrain",
  url: "https://stamen-tiles-{a-d}.a.ssl.fastly.net/terrain/{z}/{x}/{y}.png"
});

//The data table
var jsDataTable = null;

//Table selections
var selectedColumn = -1;
var selectedRows = [];

//The row number (in the data file) of the last selected/deselected row, and
//which action was performed.
var lastClickedRow = -1;
var lastClickedAction = DESELECT_ACTION;

//The callback function for the DataTables drawing call
var dataTableDrawCallback = null;

//Variables for highlighting selected row in table
var tableScrollRow = null;
var scrollEventTimer = null;
var scrollEventTimeLimit = 300;

//Keeps track of the split positions as a percentage of the
//full data area

var resizeEventTimer = null;
var tableSplitProportion = 0.5;

//Stepped range calculator
const range = (start, stop, step = 1) =>
  Array(Math.ceil((stop - start) / step)).fill(start).map((x, y) => x + y * step)

//Page Load function - kicks everything off
$(function() {
  // Make the panel splits
  $('#plotPageContent').split({orientation: 'horizontal', onDragEnd: function(){scaleTableSplit()}});
  tableSplitProportion = 0.5;

  if (typeof start == 'function') {
    start();
  }

  // When the window is resized, scale the panels
  $(window).resize(function() {
    clearTimeout(resizeEventTimer);
    resizeEventTimer = setTimeout(resizeContent, 100);
  });
});

function scaleTableSplit() {
  tableSplitProportion = $('#plotPageContent').split().position() / $('#plotPageContent').height();
  resizeContent();
}

function resizeContent() {
  // Also change in plotPage.css
  $('#plotPageContent').height(window.innerHeight - 73);

  $('#plotPageContent').split().position($('#plotPageContent').height() * tableSplitProportion);

  if (null != jsDataTable) {
    $('.dataTables_scrollBody').height(calcTableScrollY());
  }

  if (typeof resizePlots == 'function') {
    resizePlots();
  }

  if (PrimeFaces.widgets['variableDialog'] && PF('variableDialog').isVisible()) {
    resizeVariablesDialog();
  }
}

function makeJSDates(data) {

  for (var i = 0; i < data.length; i++) {
    // Replace the milliseconds value with a Javascript date
    point_data = data[i];
    point_data[0] = new Date(point_data[0]);
    data[i] = point_data;
  }

  return data;
}

/*
 * Begins the redraw of the data table.
 * The HTML table is initialised (with header only), and
 * the DataTables object is created and configured to load
 * its data from the server using the hidden form.
 */
function drawTable() {
  html = '<table id="dataTable" class="display compact nowrap" cellspacing="0" width="100%"><thead>';

  var columnHeadings = JSON.parse($('#plotPageForm\\:columnHeadings').val());

  columnHeadings.forEach(heading => {
    html += '<th>';
    html += heading;
    html += '</th>';
  });

  html += '</thead></table>';

  $('#tableContent').html(html);

  jsDataTable = $('#dataTable').DataTable( {
    ordering: false,
    searching: false,
    serverSide: true,
    scroller: {
      loadingIndicator: true
    },
    scrollY: calcTableScrollY(),
    ajax: function ( data, callback, settings ) {
      // Since we've done a major scroll, disable the short
      // scroll timeout
      clearTimeout(scrollEventTimer);
      scrollEventTimer = null;

      // Store the callback
      dataTableDrawCallback = callback;

      // Fill in the form inputs
      $('#tableForm\\:tableDataDraw').val(data.draw);
      $('#tableForm\\:tableDataStart').val(data.start);
      $('#tableForm\\:tableDataLength').val(data.length);

      // Clear the existing table data
      $('#tableForm\\:tableJsonData').val("");

      // Submit the query to the server
      tableGetData(); // PF remoteCommand
    },
    bInfo: false,
    drawCallback: function (settings) {
      if (null != tableScrollRow) {
        highlightRow(tableScrollRow);
        tableScrollRow = null;
      }
      setupTableClickHandlers();
      drawTableSelection();
    },
    columnDefs: getColumnDefs()
  });

  renderTableColumns();
  resizeContent();
  clearSelection();

  // Large table scrolls trigger highlights when the table is redrawn.
  // This handles small scrolls that don't trigger a redraw.
  $('.dataTables_scrollBody').scroll(function() {
    if (null != tableScrollRow) {
      if (scrollEventTimer) {
        clearTimeout(scrollEventTimer);
      }

      scrollEventTimer = setTimeout(function() {
        highlightRow(tableScrollRow);
        tableScrollRow = null;
      }, scrollEventTimeLimit);
    }
  });
}

/*
 * Show or hide columns as required.
 */
function renderTableColumns(fieldSet) {

  var fieldSets = JSON.parse($('#plotPageForm\\:fieldSets').val());

  // Default to the first field set
  // Note that the zeroth fieldset is Date/Position which doesn't count
  if (typeof fieldSet === 'undefined') {
    fieldSet = $('#plotPageForm\\:defaultFieldSet').val();
  }

  var visibleColumns = [0]; // Date/Time

  // Other base fieldset fields
  var fieldSetColumns = fieldSets[0];
  for (var i = 1; i < fieldSetColumns.length; i++) {
    visibleColumns.push(fieldSetColumns[i]);
  }

  // Selected fieldset fields
  var fieldSetColumns = fieldSets[fieldSet];
  for (var i = 0; i < fieldSetColumns.length; i++) {
    visibleColumns.push(fieldSetColumns[i]);
  }

  var invisibleColumns = jsDataTable.columns()[0].filter(f => !visibleColumns.includes(f));
  jsDataTable.columns(invisibleColumns).visible(false, false);
  jsDataTable.columns(visibleColumns).visible(true, true);
}

/*
 * Calculate the value of the scrollY entry for the data table
 */
function calcTableScrollY() {
  return $('#tableContent').height() - $('#footerToolbar').outerHeight();
 }

function getSelectableRows() {
  return JSON.parse($('#plotPageForm\\:selectableRows').val());
}

function getSelectableColumns() {
  return JSON.parse($('#plotPageForm\\:selectableColumns').val());
}

/*
 * Called when table data has been downloaded from the server.
 * The previously stored callback function is triggered with
 * the data from the server.
 */
function tableDataDownload() {
  dataTableDrawCallback( {
    draw: $('#tableForm\\:tableDataDraw').val(),
    data: JSON.parse($('#tableForm\\:tableJsonData').val()),
    recordsTotal: $('#tableForm\\:recordCount').val(),
    recordsFiltered: $('#tableForm\\:recordCount').val()
  });
}

/*
 * Get the Row ID from a given graph click event
 *
 * For now, this just looks up the row using the X value. This will
 * work for dates, but will need to be more intelligent for non-date plots.
 */
function getRowId(event, xValue, points) {
  var containerId = $(event.target).
  parents().
  filter(function() {
    return this.id.match(/plot[1-2]Container/)
  })[0]['id'];

  var plotIndex = containerId.substring(4, 5);
  var pointId = points[0]['idx'];

  return getPlotData(plotIndex)[pointId][1];
}

/*
 * Scroll to the table row with the given ID
 */
function scrollToTableRow(rowId) {

  var tableRow = -1;

  if (null != rowId) {
    tableRow = JSON.parse($('#plotPageForm\\:tableRowIds').val()).indexOf(rowId);
  }

  if (tableRow >= 0) {
    jsDataTable.scroller().scrollToRow(tableRow - 2);

    // Because we scroll to the row - 2, we know that the
    // row we want to highlight is the third row
    tableScrollRow = rowId;

    // The highlight is done as part of the table draw callback
  }
}

function highlightRow(rowId) {
  if (null != rowId) {
    setTimeout(function() {
      var rowNode = $('#' + rowId)[0];
      $(rowNode).css('animationName', 'rowFlash').css('animationDuration', '1s');
      setTimeout(function() {
        $(rowNode).css('animationName', '');
      }, 1000);
    }, 100);
  }
}

/*
 * Set up click handlers on table cells
 */
function setupTableClickHandlers() {

  // Remove any existing handlers
  $('.dataTable').off('click', 'tbody td');

  // Set click handler
  $('.dataTable').on('click', 'tbody td', function() {
    clickCellAction(this._DT_CellIndex, event.shiftKey);
  })
}

function canSelectCell(row, col) {

  var result = true;

  if ($.inArray(row, getSelectableRows()) < 0) {
    result = false;
  } else if ($.inArray(col, getSelectableColumns()) < 0) {
    result = false;
  }

  return result;
}

function canSelectColumn(col) {
  return getSelectableColumns().indexOf(col) > -1;
}

/*
 * Process cell clicks
 */
function clickCellAction(cellIndex, shiftClick) {

  var rowId = jsDataTable.row(cellIndex.row).data()['DT_RowId'];
  var columnIndex = cellIndex.column;
  
  // If the cell isn't selectable, or has no value, do nothing.
  if (canSelectCell(rowId, columnIndex) &&
    null != jsDataTable.cell(cellIndex).data() &&
    null != jsDataTable.cell(cellIndex).data()[0] &&
    '' != jsDataTable.cell(cellIndex).data()[0]) {

    if (columnIndex != selectedColumn) {
      selectedColumn = columnIndex;
      selectedRows = [rowId];
      lastClickedRow = rowId;
      lastClickedAction = SELECT_ACTION;
    } else {

      var action = lastClickedAction;
      var actionRows = [rowId];

      if (!shiftClick) {
        if ($.inArray(rowId, selectedRows) != -1) {
          action = DESELECT_ACTION;
        } else {
          action = SELECT_ACTION;
        }
      } else {
        actionRows = getRowsInRange(lastClickedRow, rowId, columnIndex);
      }

      if (action == SELECT_ACTION) {
        addRowsToSelection(actionRows);
      } else {
        removeRowsFromSelection(actionRows);
      }

      lastClickedRow = rowId;
      lastClickedAction = action;
    }

    selectionUpdated();
  }
}

function addRowsToSelection(rows) {
  selectedRows = selectedRows.concat(rows).sort((a, b) => a - b);
}

function removeRowsFromSelection(rows) {

  var rowsIndex = 0;
  var selectionIndex = 0;

  while (selectionIndex < selectedRows.length && rowsIndex < rows.length) {
    while (selectedRows[selectionIndex] == rows[rowsIndex]) {
      selectedRows.splice(selectionIndex, 1);
      rowsIndex++;
      if (rowsIndex == rows.length || selectionIndex == selectedRows.length) {
        break;
      }
    }
    selectionIndex++;
  }
}

function getRowsInRange(startRow, endRow, columnIndex) {

  var rows = [];

  var step = 1;
  if (endRow < startRow) {
    step = -1;
  }

  var selectableRows = getSelectableRows();

  var startIndex = selectableRows.indexOf(startRow);
  var currentIndex = startIndex;

  while (selectableRows[currentIndex] != endRow) {
    currentIndex = currentIndex + step;
    
    var rowIndex = jsDataTable.row('#' + selectableRows[currentIndex]).index();
    var cellData = jsDataTable.cell({row:rowIndex, column:columnIndex}).data();
    if (null != cellData && null != cellData[0] && '' != cellData[0]) {
      rows.push(selectableRows[currentIndex]);
    }
  }

  if (step == -1) {
    rows = rows.reverse();
  }

  return rows;
}

function selectionUpdated() {

  drawTableSelection();

  // Redraw the plots to show selection
  if (null != plot1) {
    drawPlot(1, false);
  }
  if (null != plot2) {
    drawPlot(2, false);
  }
  if (null != map1) {
    drawMap(1);
  }
  if (null != map2) {
    drawMap(2);
  }

  if (canEdit && typeof postSelectionUpdated == 'function') {
    postSelectionUpdated();
  }
}

function drawTableSelection() {
  // Clear all selection display
  $(jsDataTable.table().node()).find('.selected').removeClass('selected');

  // Highlight selected cells
  var rows = jsDataTable.rows()[0];
  for (var i = 0; i < rows.length; i++) {
    var row = jsDataTable.row(i);
    var col = jsDataTable.cell({row:i, column:selectedColumn});

    if ($.inArray(row.data()['DT_RowId'], selectedRows) > -1) {
      $(jsDataTable.cell({row : i, column : selectedColumn}).node()).addClass('selected')
    }
  }

  // Update the selection summary
  if (selectedRows.length == 0) {
    $('#selectedColumn').html('None');
    $('#selectedRowsCount').html('');
  } else {
    $('#selectedColumn').html(JSON.parse($('#plotPageForm\\:columnHeadings').val())[selectedColumn]);
    $('#selectedRowsCount').html(selectedRows.length);
  }
}


function clearSelection() {
  selectedColumn = -1;
  selectedRows = [];
  selectionUpdated();
}

function showQCMessage(qcFlag, qcMessage) {

  if (qcMessage != "") {

    var content = '';
    content += '<div class="qcInfoMessage ';

    switch (qcFlag) {
    case 3: {
      content += 'questionable';
      break;
    }
    case 4: {
      content += 'bad';
      break;
    }
    }

    content += '">';
    content += qcMessage;
    content += '</div>';

    $('#qcMessage').html(content);
    $('#qcControls').hide();
    $('#qcMessage').show();
  }
}

function hideQCMessage() {
  $('#qcMessage').hide();
  $('#qcControls').show();
}

function drawPlot(index, resetZoom) {

  var plotVar = 'plot' + index;

  var xAxisRange = null;
  var yAxisRange = null;
  
  if (!resetZoom && null != window[plotVar]) {
    xAxisRange = window[plotVar].xAxisRange();
    yAxisRange = window[plotVar].yAxisRange();
  }
  
  // Get the plot data
  var plotData = null;
  // TODO 0 = date - but we need to make it a proper lookup
  if ($('#plot' + index + 'Form\\:xAxis').val() == 0) {
    plotData = makeJSDates(getPlotData(index));
  } else {
    plotData = getPlotData(index);
  }

  var plotHighlights = makeHighlights(plotData);
  var targetValue = null;

  if (typeof getPlotTargetValue == 'function') {
    targetValue = getPlotTargetValue(index);
  }

  // Remove the existing plot
  if (null != window[plotVar]) {
    window[plotVar].destroy();
  }

  var interactionModel = getInteractionModel(index);
  var labels = getPlotLabels(index);
  var xLabel = labels[0];
  var yLabels = labels.slice(4);
  var yLabel = yLabels[0];

  var graph_options = Object.assign({}, BASE_GRAPH_OPTIONS);
  graph_options.labels = getPlotLabels(index);
  graph_options.xlabel = xLabel;
  graph_options.ylabel = yLabel;
  graph_options.visibility = getPlotVisibility(index);
  graph_options.interactionModel = interactionModel;
  graph_options.width = $('#plot' + index + 'Panel').width();
  graph_options.height = $('#plot' + index + 'Panel').height() - 40;
  graph_options.labelsDiv = 'plot' + index + 'Label';
  // Ghost data and series data colors
  graph_options.colors = ['#C0C0C0', '#01752D'];
  
  // Zoom
  if (!resetZoom) {
    graph_options.dateWindow = xAxisRange;
    graph_options.valueRange = yAxisRange;
    graph_options.yRangePad = 0;
    graph_options.xRangePad = 0;
  }

  if (typeof customiseGraphOptions == 'function') {
    graph_options = customiseGraphOptions(graph_options);
  }

  graph_options.underlayCallback = function(canvas, area, g) {

    // POINT HIGHLIGHTS
    for (var i = 0; i < plotHighlights.length; i++) {
      var fillStyle = null;

      if (plotHighlights[i][3]) {
        fillStyle = HIGHLIGHT_COLORS[SELECTION_POINT];
      } else if (null != plotHighlights[i][2]) {
        fillStyle = plotHighlights[i][2];
      }

      if (null != fillStyle) {
        var xPoint = g.toDomXCoord(plotHighlights[i][0]);
        var yPoint = g.toDomYCoord(plotHighlights[i][1]);
        canvas.fillStyle = fillStyle;
        canvas.beginPath();
        canvas.arc(xPoint, yPoint, PLOT_FLAG_SIZE, 0, 2 * Math.PI, false);
        canvas.fill();
      }
    }

    // TARGET VALUE
    if (null != targetValue) {
      var xmin = g.toDomXCoord(g.xAxisExtremes()[0]);
      var xmax = g.toDomXCoord(g.xAxisExtremes()[1]);
      var ycoord = g.toDomYCoord(targetValue);

      canvas.setLineDash([10, 5]);
      canvas.strokeStyle = '#FF0000';
      canvas.lineWidth = 3;
      canvas.beginPath();
      canvas.moveTo(xmin, ycoord);
      canvas.lineTo(xmax, ycoord);
      canvas.stroke();
      canvas.setLineDash([]);
    }
  }

  window[plotVar] = new Dygraph (
      document.getElementById('plot' + index + 'Container'),
      plotData,
      graph_options
  );

  var plotVariable = parseInt($('#plot' + index + 'Form\\:yAxis').val());
  if (canSelectColumn(getColumnIndex(plotVariable))) {
    enablePlotSelect(index);
  } else {
    disablePlotSelect(index);
  }

  window['map' + index] = null;
}

function getPlotVisibility(index) {

  var labels = getPlotLabels(index);

  var visibility = [];

  for (var i = 1; i < labels.length; i++) {
    switch (labels[i]) {
    case 'ID':
    case 'QC Flag': {
      visibility.push(false);
      break;
    }
    default: {
      visibility.push(true);
    }
    }
  }

  return visibility;
}

function getPlotLabels(index) {
  return JSON.parse($('#plot' + index + 'Form\\:plotLabels').val());
}

function getPlotData(index) {
  return JSON.parse($('#plot' + index + 'Form\\:plotData').val());
}

function getFlagText(flag) {
  var flagText = "";

  if (flag == '-1001') {
    flagText = 'Needs Flag';
  } else if (flag == '-1002') {
    flagText = 'Ignore';
  } else if (flag == '-2') {
    flagText = 'Assumed Good';
  } else if (flag == '2') {
    flagText = 'Good';
  } else if (flag == '3') {
    flagText = 'Questionable';
  } else if (flag == '4') {
    flagText = 'Bad';
  } else if (flag == '44') {
    flagText = 'Fatal';
  } else {
    flagText = 'Needs Flag';
  }

  return flagText;
}

function getFlagClass(flag) {
  var flagClass = "";

  if (flag == '-1001') {
    flagClass = 'needsFlagging';
  } else if (flag == '-1002') {
    flagClass = 'ignore';
  } else if (flag == '-2') {
    flagClass = 'assumedGood';
  } else if (flag == '2') {
    flagClass = 'good';
  } else if (flag == '3') {
    flagClass = 'questionable';
  } else if (flag == '4' || flag == '44') {
    flagClass = 'bad';
  } else {
    flagClass = 'needsFlagging';
  }

  return flagClass;
}

function showVariableDialog(plotIndex) {

  variablesPlotIndex = plotIndex;

  var mode = getPlotMode(plotIndex);

  if (mode == 'plot') {
    setupPlotVariables(plotIndex);
  } else if (mode == 'map') {
    setupMapVariables(plotIndex);
  }

  PF('variableDialog').show();
  resizeVariablesDialog();
}

function setupPlotVariables(plotIndex) {
  variableIds.forEach(id => {
    var xWidget = PrimeFaces.widgets['xAxis-' + id];
    if (xWidget) {
      xWidget.jq.show();
    }

    var yWidget = PrimeFaces.widgets['yAxis-' + id];
    if (yWidget) {
        yWidget.jq.show();
      }

    var mapWidget = PrimeFaces.widgets['mapVar-' + id];
    if (mapWidget) {
      mapWidget.jq.hide();
    }
  });

  updateAxisButtons('x', $('#plot' + variablesPlotIndex + 'Form\\:xAxis').val());
  updateAxisButtons('y', $('#plot' + variablesPlotIndex + 'Form\\:yAxis').val());
}

//Select the specified axis variable in the dialog
function updateAxisButtons(axis, variable) {

  if (!updatingButtons) {
    updatingButtons = true;

    variableIds.forEach(id => {
      var widget = PrimeFaces.widgets[axis + 'Axis-' + id];

      // Not all variables will have an axis button
      if (widget) {
        if (id == variable) {
          widget.check();
          $('#plot' + variablesPlotIndex + 'Form\\:' + axis + 'Axis').val(variable);
        } else {
          widget.uncheck();
        }
      }
    });

    updatingButtons = false;
  }
}

function setupMapVariables(plotIndex) {
  variableIds.forEach(id => {
    var xWidget = PrimeFaces.widgets['xAxis-' + id];
    if (xWidget) {
      xWidget.jq.hide();
    }

    var yWidget = PrimeFaces.widgets['yAxis-' + id];
    if (yWidget) {
        yWidget.jq.hide();
      }

    var mapWidget = PrimeFaces.widgets['mapVar-' + id];
    if (mapWidget) {
      mapWidget.jq.show();
    }
  });
  updateMapCheckboxes($('#plot' + plotIndex + 'Form\\:mapVariable').val());
}

//Select the specified variable in the dialog
function updateMapCheckboxes(variable) {

  if (!updatingButtons) {
  updatingButtons = true;

    variableIds.forEach(id => {
        var widget = PrimeFaces.widgets['mapVar-' + id];

        // Not all variables will have an axis button
        if (widget) {
          if (id == variable) {
            widget.check();
            $('#plot' + variablesPlotIndex + 'Form\\:mapVariable').val(variable);
          } else {
            widget.uncheck();
          }
        }
      });

    updatingButtons = false;
  }
}

function resizeVariablesDialog() {
  var varList = $('#variablesList');
  varList.width(200);

  var maxHeight = $(window).innerHeight() - 200;

  var varsPerColumn = Math.ceil(maxHeight / VARIABLES_DIALOG_ENTRY_HEIGHT);

  var columns = Math.ceil(variableCount / varsPerColumn);

  if (columns == 1 && variableCount < 5) {
    varsPerColumn = variableCount;
  } else if (columns < 2 && variableCount > 5) {
    columns = 2;
    varsPerColumn = Math.ceil(variableCount / 2);
  }

  varList.height(varsPerColumn * VARIABLES_DIALOG_ENTRY_HEIGHT + 30);

  PF('variableDialog').jq.width(varList.prop('scrollWidth') + 50);
  PF('variableDialog').initPosition();

  variablesDialogSized = true;
}

function applyVariables() {
  if (PrimeFaces.widgets['variableDialog']) {
    PF('variableDialog').hide();
  }

  var mode = getPlotMode(variablesPlotIndex);

  // Clear all current data
  $('#plot' + variablesPlotIndex + 'Form\\:plotData').val("");
  $('#plot' + variablesPlotIndex + 'Form\\:mapData').val("");

  if (mode == 'plot') {
    eval('plot' + variablesPlotIndex + 'GetData()'); // PF remoteCommand
  } else if (mode == 'map') {
    eval('map' + variablesPlotIndex + 'GetData()'); // PF remoteCommand
    initMap(variablesPlotIndex);
  }

}

function getSelectedMapVar() {

  var result = -1;

  var id = 0;
  var finished = false;

  while (!finished) {
    var widget = PrimeFaces.widgets['mapVar-' + id];
    if (null == widget) {
      finished = true;
    } else {
      if (widget.input.prop('checked')) {
        result = id;
        finished = true;
      }
    }

    id++;
  }

  return result;
}

function updatePlot(plotIndex) {

  if (PrimeFaces.widgets['variableDialog']) {
    PF('variableDialog').hide();
  }

  var mode = getPlotMode(plotIndex);

  if (mode == "plot") {
    drawPlot(plotIndex, true);
  } else {
    initMap(plotIndex);
  }
  
  plotLoaded(plotIndex);
}

function redrawPlot(index) {
  variablesPlotIndex = index;
  applyVariables();
}

function initMap(index) {
  $('#map' + index +'Container').empty()
  $('#map' + index + 'Container').width($('#plot' + index + 'Panel').width());
  $('#map' + index + 'Container').height($('#plot' + index + 'Panel').height() - 40);

  var mapVar = 'map' + index;
  var extentVar = mapVar + 'Extent';

  var bounds = JSON.parse($('#plotPageForm\\:dataBounds').val());
  window[mapVar] = null;
  var initialView = new ol.View({
    center: ol.proj.fromLonLat([bounds[4], bounds[5]]),
    zoom: 4,
    minZoom: 2,
  });

  window[mapVar] = new ol.Map({
    target: 'map' + index + 'Container',
    layers: [
      new ol.layer.Tile({
        source: mapSource
      }),
      ],
      controls: [
        new ol.control.Zoom()
        ],
        view: initialView
  });

  window[extentVar] = ol.proj.transformExtent(bounds.slice(0, 4), "EPSG:4326", initialView.getProjection());

  window[mapVar].on('moveend', function(event) {
    mapMoveGetData(event);
  });
  window[mapVar].on('pointermove', function(event) {
    displayMapFeatureInfo(event, window[mapVar].getEventPixel(event.originalEvent));
  });
  window[mapVar].on('click', function(event) {
    mapClick(event, window[mapVar].getEventPixel(event.originalEvent));
  });

  $('#plot' + index + 'Form\\:mapUpdateScale').val(true);
  redrawMap = true;
  getMapData(index);
}


function mapMoveGetData(event) {
  getMapData(getMapIndex(event));
  redrawMap = false;
}

function getMapData(index) {
  var mapVar = 'map' + index;
  var extent = ol.proj.transformExtent(window[mapVar].getView().calculateExtent(), window[mapVar].getView().getProjection(), "EPSG:4326");
  $('#plot' + index + 'Form\\:mapBounds').val('[' + extent + ']');
  $('#plot' + variablesPlotIndex + 'Form\\:plotData').val("");
  $('#plot' + variablesPlotIndex + 'Form\\:mapData').val("");
  eval('map' + index + 'GetData()');
}

function drawMap(index) {
  var mapVar = 'map' + index;
  var dataLayerVar = mapVar + 'DataLayer';
  var colorScaleVar = mapVar + 'ColorScale';
  if (null != window[dataLayerVar]) {
    window[mapVar].removeLayer(window[dataLayerVar]);
    window[dataLayerVar] = null;
  }
  var mapData = JSON.parse($('#plot' + index + 'Form\\:mapData').val());

  var plotHighlights = makeMapHighlights(mapData);

  var scaleLimits = JSON.parse($('#plot' + index + 'Form\\:mapScaleLimits').val());
  window[colorScaleVar].setValueRange(scaleLimits[0], scaleLimits[1]);

  var layerFeatures = new Array();

  for (var i = 0; i < mapData.length; i++) {
    var featureData = mapData[i];

    var feature = new ol.Feature({
      geometry: new ol.geom.Point([featureData[0], featureData[1]]).transform(ol.proj.get("EPSG:4326"), mapSource.getProjection())
    });
    var stroke = null
    if (mapData[i][MAP_MEASUREMENT_ID_INDEX] in plotHighlights) {
      var color = plotHighlights[mapData[i][MAP_MEASUREMENT_ID_INDEX]][0]
      if (plotHighlights[mapData[i][MAP_MEASUREMENT_ID_INDEX]][1]) {
        // Point is selected
        color = HIGHLIGHT_COLORS[SELECTION_POINT]
      }
      stroke = new ol.style.Stroke({
        color: color,
        width: 2
      });
    }
    feature.setStyle(
      new ol.style.Style({
        image: new ol.style.Circle({
          radius: 5,
          fill: new ol.style.Fill({
            color: window[colorScaleVar].getColor(featureData[4])
          }),
          stroke: stroke
        })
      })
    );

    feature['data'] = featureData;
    feature['tableRow'] = featureData[2];

    layerFeatures.push(feature);
  }

  window[dataLayerVar] = new ol.layer.Vector({
    source: new ol.source.Vector({
      features: layerFeatures
    })
  })

  window[mapVar].addLayer(window[dataLayerVar]);
  window[colorScaleVar].drawScale($('#map' + index + 'Scale'), scaleOptions);

  if (redrawMap) {
    $('#plot' + variablesPlotIndex + 'Form\\:mapUpdateScale').val(false);

    var bounds = JSON.parse($('#plot' + index + 'Form\\:mapBounds').val());
    window['map' + index + 'Extent'] = ol.proj.transformExtent(bounds.slice(0, 4), "EPSG:4326", window[mapVar].getView().getProjection());
    resetZoom(index);
  }

  // Destroy the plot, which is no longer visible
  window['plot' + index] = null;
}

function displayMapFeatureInfo(event, pixel) {
  var index = getMapIndex(event);

  var feature = window['map' + index].forEachFeatureAtPixel(pixel, function(feature) {
    return feature;
  });

  var featureInfo = '';

  if (feature) {
    featureInfo += '<b>Position:</b> '
      featureInfo += feature['data'][0];
    featureInfo += ' ';
    featureInfo += feature['data'][1];
    featureInfo += ' ';
    featureInfo += ' <b>Value:</b> '
      featureInfo += feature['data'][4];
  }

  $('#map' + index + 'Value').html(featureInfo);
}

function mapClick(event, pixel) {
  var index = getMapIndex(event);
  var feature = window['map' + index].forEachFeatureAtPixel(pixel, function(feature) {
    return feature;
  });

  if (feature) {
    scrollToTableRow(feature['data'][2]);
  }
}

function resetZoom(index) {
  var mode = getPlotMode(index)

  if (mode == 'map') {
    var bounds = JSON.parse($('#plotPageForm\\:dataBounds').val());
    window['map' + index + 'Extent'] = ol.proj.transformExtent(bounds.slice(0, 4), "EPSG:4326", window['map' + index].getView().getProjection());
    window['map' + index].getView().fit(window['map' + index + 'Extent'], window['map' + index].getSize());
  } else {
    window['plot' + index].updateOptions({
      yRangePad: 10,
      xRangePad: 10
    });
    
    window['plot' + index].resetZoom();
  }
}

function getMapIndex(event) {
  var containerName = event.target.getTarget();
  return containerName.match(/map([0-9])/)[1];

}

function toggleScale(index) {
  $('#map' + index + 'Scale').toggle(100, function() {
    window['map' + index + 'ScaleVisible'] =
      ($('#map' + index + 'Scale').css('display') === 'block');
  });
}

function makeHighlights(plotData) {
  var highlights = [];

  var currentFlag = FLAG_GOOD;
  var highlightColor = null;

  for (var i = 0; i < plotData.length; i++) {
    var selected = binarySearch(selectedRows, plotData[i][PLOT_MEASUREMENT_ID_INDEX]) > -1;

    var manualFlag = plotData[i][PLOT_MANUAL_FLAG_INDEX]

    if (selected ||
      (Math.abs(manualFlag) != FLAG_GOOD && manualFlag != FLAG_FLUSHING)) {
      
      highlightColor = null;
      if (plotData[i][PLOT_MANUAL_FLAG_INDEX] in HIGHLIGHT_COLORS ) {
        highlightColor = HIGHLIGHT_COLORS[plotData[i][PLOT_MANUAL_FLAG_INDEX]]
      }
      for (j = PLOT_FIRST_Y_INDEX; j < plotData[i].length; j++) {
        if (plotData[i][j] != null) {
          highlights.push([
            plotData[i][0],
            plotData[i][j],
            highlightColor,
            selected
          ])
        }
      }
    }
  }

  return highlights;
}
function makeMapHighlights(mapData) {
  var highlights = {};

  for (var i = 0; i < mapData.length; i++) {
    var selected = binarySearch(selectedRows, mapData[i][MAP_MEASUREMENT_ID_INDEX]) > -1;

    if (selected || Math.abs(mapData[i][MAP_MANUAL_FLAG_INDEX]) != FLAG_GOOD) {
      let highlightColor = null;
      if (mapData[i][MAP_MANUAL_FLAG_INDEX] in HIGHLIGHT_COLORS ) {
        highlightColor = HIGHLIGHT_COLORS[mapData[i][MAP_MANUAL_FLAG_INDEX]]
      }
      highlights[mapData[i][MAP_MEASUREMENT_ID_INDEX]] = [
        highlightColor,
        selected
      ]
    }
  }

  return highlights;
}

function getPlotMode(index) {
  var mode = $('[id^=plot' + index + 'Form\\:plotMode]:checked').val();
  if (mode === undefined) {
    mode = $('[id^=plot' + index + 'Form\\:plotMode]').val();
  }

  return mode;
}

// Get the interaction model for a plot
function getInteractionModel(index) {
  var plot = window['plot' + index];
  var selectMode = $('[id^=plot' + index + 'Form\\:plotSelectMode]:checked').val();

  var interactionModel = null;

  if (selectMode == 'select') {
    interactionModel = {
      mousedown: selectModeMouseDown,
      mouseup: selectModeMouseUp,
      mousemove: selectModeMouseMove
    }
  } else {
  // Use the default interaction model, but without
  // double-click. We use the clickCallback property defined
  // in BASE_GRAPH_OPTIONS above
  interactionModel = Dygraph.defaultInteractionModel;
    interactionModel.dblclick = null;
  }

  return interactionModel;
}

function setPlotSelectMode(index) {
  drawPlot(index, false);
}

function selectModeMouseDown(event, g, context) {
  context.isZooming = true;
  context.dragStartX = dragGetX(g, event);
  context.dragStartY = dragGetY(g, event);
  context.dragEndX = context.dragStartX;
  context.dragEndY = context.dragStartY;
  context.prevEndX = null;
  context.prevEndY = null;
}

function selectModeMouseMove(event, g, context) {
  if (context.isZooming) {
    context.dragEndX = dragGetX(g, event);
    context.dragEndY = dragGetY(g, event);
    drawSelectRect(g, context);
    context.prevEndX = context.dragEndX;
    context.prevEndY = context.dragEndY;
  }
}

function selectModeMouseUp(event, g, context) {

  g.clearZoomRect_();

  var plotId = g.maindiv_.id.substring(4,5);
  var plotVar = parseInt($('#plot' + plotId + 'Form\\:yAxis').val());

  if (canSelectColumn(getColumnIndex(plotVar))) {
    var minX = g.toDataXCoord(context.dragStartX);
    var maxX = g.toDataXCoord(context.dragEndX);
    if (maxX < minX) {
      minX = maxX;
      maxX = g.toDataXCoord(context.dragStartX);
    }

    var minY = g.toDataYCoord(context.dragStartY);
    var maxY = g.toDataYCoord(context.dragEndY);
    if (maxY < minY) {
      minY = maxY;
      maxY = g.toDataYCoord(context.dragStartY);
    }

    // If we've only moved the mouse by a small amount,
    // interpret it as a click
    var xDragDistance = Math.abs(context.dragEndX - context.dragStartX);
    var yDragDistance = Math.abs(context.dragEndY - context.dragStartY);

    if (xDragDistance <= 3 && yDragDistance <= 3) {
      var closestPoint = g.findClosestPoint(context.dragEndX, context.dragEndY, undefined);
      var pointId = closestPoint.point['idx'];
      var row = getPlotData(plotId)[pointId][1];
      scrollToTableRow(row);
    } else {
      selectPointsInRect(getPlotData(plotId), plotVar, minX, maxX, minY, maxY);
    }
  }
}

function drawSelectRect(graph, context) {
  var ctx = graph.canvas_ctx_;

  if (null != context.prevEndX && null != context.prevEndY) {
    ctx.clearRect(context.dragStartX, context.dragStartY,
      (context.prevEndX - context.dragStartX),
      (context.prevEndY - context.dragStartY))
  }

  ctx.fillStyle = "rgba(128,128,128,0.33)";
  ctx.fillRect(context.dragStartX, context.dragStartY,
    (context.dragEndX - context.dragStartX),
    (context.dragEndY - context.dragStartY))
}

function dragGetX(graph, event) {
  return  event.clientX - graph.canvas_.getBoundingClientRect().left;
}

function dragGetY(graph, event) {
  return event.clientY - graph.canvas_.getBoundingClientRect().top;
}

function selectPointsInRect(data, variableId, minX, maxX, minY, maxY) {
  var pointsToSelect = [];

  for (var i = 0; i < data.length; i++) {
    if (data[i][0] > maxX) {
      break;
    } else if (data[i][0] >= minX) {
      // See if any of the Y values are in range
      for (var y = 4; y < data[i].length; y++) {
        if (data[i][y] >= minY && data[i][y] <= maxY) {
          pointsToSelect.push(data[i][1]);
          break;
        }
      }
    }
  }

  newSelectedColumn = getColumnIndex(variableId);
  if (newSelectedColumn != selectedColumn) {
    selectedRows = pointsToSelect;
    selectedColumn = newSelectedColumn;
  } else {
    addRowsToSelection(pointsToSelect);
  }

  selectionUpdated();
}

function getColumnIndex(varId) {
  return JSON.parse($('#plotPageForm\\:columnIDs').val()).indexOf(varId);
}

function binarySearch (arr, val) {
  let start = 0;
  let end = arr.length - 1;

  while (start <= end) {
      let mid = Math.floor((start + end) / 2);

      if (arr[mid] === val) {
          return mid;
      }
      if (val < arr[mid]) {
          end = mid - 1;
      } else {
          start = mid + 1;
      }
  }
  return -1;
}

function nrt() {
  return $('#plotPageForm\\:nrt').val();
}

function enablePlotSelect(index) {
  // TODO This works by messing with the CSS, because the version
  // of PrimeFaces we're using doesn't work properly.
  // Sort it out when we upgrade.

  if (!nrt()) {
    PF('plot' + index + 'SelectMode').buttons.eq(1).removeClass('ui-state-disabled');
  }
}

function plotLoading(index) {
  $('#plot' + index + 'Loading').show();
}

function plotLoaded(index) {
  $('#plot' + index + 'Loading').hide();
}