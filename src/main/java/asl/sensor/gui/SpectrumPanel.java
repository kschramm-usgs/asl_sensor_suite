package asl.sensor.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;

import javax.swing.JCheckBox;
import javax.swing.JPanel;

import org.jfree.chart.axis.LogarithmicAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.data.xy.XYSeriesCollection;

import asl.sensor.experiment.ExperimentEnum;
import asl.sensor.experiment.SpectrumExperiment;
import asl.sensor.input.DataStore;

/**
 * Panel for displaying the results of the self-noise experiment (3-input).
 * In addition to general requirements of output panels, also includes
 * a checkbox to choose between frequency and interval x-axis and
 * the variant axes for when that box is checked.
 * @author akearns
 *
 */
public class SpectrumPanel extends ExperimentPanel {

  /**
   * Auto-generated serialize ID
   */
  private static final long serialVersionUID = 9018553361096758354L;
  
  protected JCheckBox freqSpaceBox;
  private int plotCount;
  // three PSDs, three self-noise calcs
  
  protected final Color[] COLORS = {Color.RED, Color.BLUE, Color.GREEN};
  
  private NumberAxis freqAxis;
  
  /**
   * Constructs a new panel and lays out all the components in it
   * @param exp
   */
  public SpectrumPanel(ExperimentEnum exp) {
    
    // create chart, chartPanel, save button & file chooser, 
    super(exp);
    
    plotCount = 0;
    
    for (int i = 0; i < 3; ++i) {
      channelType[i] = "Input data (RESP required)";
    }
    
    plotTheseInBold = new String[]{"NLNM","NHNM"};
    
    // instantiate local fields
    String xAxisTitle = "Period (s)";
    String freqAxisTitle = "Frequency (Hz)";
    String yAxisTitle = "Power (rel. 1 (m/s^2)^2/Hz)";
    xAxis = new LogarithmicAxis(xAxisTitle);
    freqAxis = new LogarithmicAxis(freqAxisTitle);
    yAxis = new NumberAxis(yAxisTitle);
    yAxis.setAutoRange(true);
    ( (NumberAxis) yAxis).setAutoRangeIncludesZero(false);
    Font bold = xAxis.getLabelFont().deriveFont(Font.BOLD);
    xAxis.setLabelFont(bold);
    yAxis.setLabelFont(bold);
    freqAxis.setLabelFont(bold);
    
    freqSpaceBox = new JCheckBox("Use Hz units (requires regen)");
    freqSpaceBox.setSelected(false);
    
    applyAxesToChart(); // now that we've got axes defined
    
    // set the GUI components
    this.setLayout( new GridBagLayout() );
    GridBagConstraints gbc = new GridBagConstraints();
    
    gbc.fill = GridBagConstraints.BOTH;
    gbc.gridx = 0; gbc.gridy = 0;
    gbc.weightx = 1.0; gbc.weighty = 1.0;
    gbc.gridwidth = 3;
    gbc.anchor = GridBagConstraints.CENTER;
    this.add(chartPanel, gbc);
    
    // place the other UI elements in a single row below the chart
    gbc.gridwidth = 1;
    gbc.weighty = 0.0; gbc.weightx = 0.0;
    gbc.anchor = GridBagConstraints.WEST;
    gbc.fill = GridBagConstraints.NONE;
    gbc.gridy += 1; gbc.gridx = 0;
    this.add(freqSpaceBox, gbc);
    
    gbc.gridx += 1;
    gbc.weightx = 1.0;
    gbc.fill = GridBagConstraints.NONE;
    gbc.anchor = GridBagConstraints.CENTER;
    // gbc.gridwidth = GridBagConstraints.REMAINDER;
    this.add(save, gbc);
    
    // add an empty panel as a spacer to keep the save button in the center
    gbc.fill = GridBagConstraints.NONE;
    gbc.gridx += 1;
    gbc.weightx = 0;
    gbc.anchor = GridBagConstraints.WEST;
    JPanel spacer = new JPanel();
    spacer.setPreferredSize( freqSpaceBox.getPreferredSize() );
    this.add(spacer, gbc);
  }
  
  @Override
  public void actionPerformed(ActionEvent e) {
    // overridden in the event we add more stuff to this panel
    super.actionPerformed(e); // only actionlistener here
  }
  
  @Override
  protected void drawCharts() {
    
    setChart( expResult.getData().get(0) );

    chartPanel.setChart(chart);
    chartPanel.setMouseZoomable(true);
    
  }

  /**
   * Gets the x-axis for this panel based on whether or not the
   * selection box to plot in units of Hz is selected. If it is, this
   * plot will have frequency units of Hz in the x-axis, otherwise it will have
   * interval units of seconds in it
   */
  @Override
  public ValueAxis getXAxis() {
    
    // true if using Hz units
    if ( freqSpaceBox.isSelected() ) {
        return freqAxis;
    }
    
    return xAxis;
    
  }

  @Override
  public int panelsNeeded() {
    return 3;
  }
  
  @Override
  public int plotsToShow() {
    return plotCount;
  }
  
  /**
   * Initially called function to calculate self-noise when data is passed in
   */
  @Override
  protected void updateData(final DataStore ds) {
    
    set = true;
    
    plotCount = 0;
    for (int i = 0; i < panelsNeeded(); ++i) {
      if ( ds.bothComponentsSet(i) ) {
        ++plotCount;
      }
    }
    
    boolean freqSpace = freqSpaceBox.isSelected();
    
    final boolean freqSpaceImmutable = freqSpace;
    
    SpectrumExperiment psdExp = (SpectrumExperiment) expResult;
    psdExp.setFreqSpace(freqSpaceImmutable);
    expResult.runExperimentOnData(ds);

    XYSeriesCollection xysc = expResult.getData().get(0);

    for (int i = 0; i < plotCount; ++i) {
      String name = (String) xysc.getSeriesKey(i);
      Color plotColor = COLORS[i % 3];
      seriesColorMap.put(name, plotColor);
    }
  }
  

}
