package asl.sensor.input;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.complex.ComplexFormat;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealVector;

import asl.sensor.gui.InputPanel;
import asl.sensor.utils.NumericUtils;

/**
 * This class is used to read in and store data from instrument response files
 * such as those found on the IRIS RESP database. This includes functions used
 * to parse in relevant data from those files to get values such as poles and
 * zeros and the gain stages of the detector/sensor setup.
 * See also: http://ds.iris.edu/ds/nodes/dmc/data/formats/resp/
 * @author akearns
 *
 */
public class InstrumentResponse {

  public static final double PEAK_MULTIPLIER = 0.8;
  
  /**
   * Get one of the response files embedded in the program
   * @return response file embedded into the program
   * @throws IOException If no file with the given name exists (this may happen
   * if a file listed in the responses.txt file does not exist in that location
   * which means it was likely improperly modified or a response file deleted)
   */
  public static InstrumentResponse loadEmbeddedResponse(String fname) 
      throws IOException {
    
    ClassLoader cl = InputPanel.class.getClassLoader();
    InputStream is = cl.getResourceAsStream(fname);
    BufferedReader fr = new BufferedReader( new InputStreamReader(is) );
    return new InstrumentResponse(fr, fname);
  }
  
  /**
   * Get list of all responses embedded into the program, derived from the
   * responses.txt file in the resources folder
   * @return Set of strings representing response filenames
   */
  public static Set<String> parseInstrumentList() {
    
    Set<String> respFilenames = new HashSet<String>();
    ClassLoader cl = InstrumentResponse.class.getClassLoader();
    
    // there's no elegant way to extract responses other than to
    // load in their names from a list and then grab them as available
    // correspondingly, this means adding response files to this program
    // requires us to add their names to this file
    // There may be other possibilities but they are more complex and
    // tend not to work the same way between IDE and launching a jar
    
    InputStream respRead = cl.getResourceAsStream("responses.txt");
    BufferedReader respBuff = 
        new BufferedReader( new InputStreamReader(respRead) );

    try {
      String name;
      name = respBuff.readLine();
      while (name != null) {
        respFilenames.add(name);
        name = respBuff.readLine();
      }
      respBuff.close();
    } catch (IOException e2) {
      e2.printStackTrace();
    }
    
    return respFilenames;
  }
  
  /**
   * Extract the real and imaginary terms from a pole or zero in a RESP file
   * @param line the line the zero or pole is found on in the file
   * @param array the array of zeros and poles the term will be added to
   */
  private static void parseTermAsComplex(String line, Complex[] array) {
    // reparse the line. why are we doing this? well,
    // if a number is negative, only one space between it and prev. number
    // and the previous split operation assumed > 2 spaces between numbers
    String[] words = line.split("\\s+");


    // index 0 is the identifier for the field types (used in switch-stmt)
    // index 1 is where in the list this zero or pole is
    // index 2 is the real part, and index 3 the imaginary
    // indices 4 and 5 are error terms (ignored)    
    int index = Integer.parseInt(words[1]);
    double realPart = Double.parseDouble(words[2]);
    double imagPart = Double.parseDouble(words[3]);
    array[index] = new Complex(realPart, imagPart);
  }
  
  private TransferFunction transferType;
  private int epochsCounted;
  
  // gain values, indexed by stage
  private double[] gain;
  private int numStages;
  
  // poles and zeros
  private Map<Complex, Integer> zeros;
  
  private Map<Complex, Integer> poles;
  
  private String name;
  private Unit unitType;
  
  private double normalization; // A0 normalization factor
  
  private double normalFreq; // cuz she's a normalFreq, normalFreq 
  // (the A0 norm. factor's frequency) 
  
  /**
   * Reads in a response from an already-accessed bufferedreader handle
   * and assigns it to the name given (used with embedded response files)
   * Only the last epoch of a multi-epoch response file is used.
   * @param br Handle to a buffered reader of a given RESP file
   * @param name Name of RESP file to be used internally 
   * @throws IOException
   */
  public InstrumentResponse(BufferedReader br, String name) throws IOException {
    
    this.name = name;
    
    parserDriver(br);
  }
  /**
   * Create a copy of an existing response object
   * @param responseIn The response object to be copied
   */
  public InstrumentResponse(InstrumentResponse responseIn) {
    epochsCounted = 1;
    transferType = responseIn.getTransferFunction();
    
    gain = responseIn.getGain();
    numStages = responseIn.getNumStages();

    zeros = new HashMap<Complex, Integer>( responseIn.getZerosMap() );
    poles = new HashMap<Complex, Integer>( responseIn.getPolesMap() );
    
    unitType = responseIn.getUnits();
    
    normalization = Double.valueOf( responseIn.getNormalization() );
    normalFreq = Double.valueOf( responseIn.getNormalizationFrequency() );
    
    name = responseIn.getName();
  }
  
  public int getNumStages() {
    return numStages;
  }

  private Map<Complex, Integer> getZerosMap() {
    return zeros;
  }
  
  private Map<Complex, Integer> getPolesMap() {
    return poles;
  }

  private List<Complex> getSortedPoleKeys() {
    ArrayList<Complex> list = new ArrayList<Complex>( poles.keySet() );
    if (list.size() > 1) {
      NumericUtils.complexMagnitudeSorter(list);
    }
    return list;
  }
  
  private List<Complex> getSortedZeroKeys() {
    ArrayList<Complex> list = new ArrayList<Complex>( zeros.keySet() );
    if ( list.size() > 1 ) {
      NumericUtils.complexMagnitudeSorter(list);
    }
    return list;
  }
  
  /**
   * Reads in an instrument response from a RESP file
   * If the RESP file has multiple epochs, only the last one is used.
   * @param filename full path of the RESP file
   * @throws IOException
   */
  public InstrumentResponse(String filename) throws IOException {
    name = new File(filename).getName();
    parseResponseFile(filename);
  }
  
  /**
   * Apply the values of this response object to a list of frequencies and
   * return the resulting (complex) frequencies
   * The response curve produced is in units of velocity. Some results
   * will need to have the produced response curve have acceleration units,
   * which can be done by multiplying by the integration factor defined here.
   * @param frequencies inputted list of frequencies, such as FFT windows
   * @return application of the response to those frequencies
   */
  public Complex[] applyResponseToInput(double[] frequencies) {
   
    Complex[] resps = new Complex[frequencies.length];
    
    // precalculate gain for scaling the response
    double scale = 1.;
    // stage 0 is sensitivity (supposed to be product of all gains)
    // we will get scale by multiplying all gain stages except for it
    for (int i = 1; i < gain.length; ++i) {
      scale *= gain[i];
    }
    
    // how many times do we need to do differentiation?
    // outUnits (acceleration) - inUnits
    // i.e., if the units of this response are acceleration, we integrate once
    int diffs = Unit.VELOCITY.getDifferentiations(unitType);
    // unlike s (see below) this is always 2Pi
    double integConstant = NumericUtils.TAU;
    
    for (int i = 0; i < frequencies.length; ++i) {
      double deltaFrq = frequencies[i];
      
      // pole-zero expansion
      Complex s = new Complex( 0, deltaFrq*transferType.getFunction() );
      
      Complex numerator = Complex.ONE;
      Complex denominator = Complex.ONE;
      
      for ( Complex zero : zeros.keySet() ) {
        int count = zeros.get(zero); // number of times to apply zero
        for (int j = 0; j < count; ++j) {
          numerator = numerator.multiply( s.subtract(zero) );  
        }
      }
      
      for ( Complex pole : poles.keySet() ) {
        int count = poles.get(pole);
        for (int j = 0; j < count; ++j) {
          denominator = denominator.multiply( s.subtract(pole) );
        }
      }
      
      resps[i] = numerator.multiply(normalization).divide(denominator);
      
      if (diffs < 0) {
        // a negative number of differentiations 
        // is a positive number of integrations
        // i*omega; integration is I(w) x (iw)^n
        Complex iw = new Complex(0.0, integConstant*deltaFrq);
        // start at 1 in these loops because we do mult. at least once
        for (int j = 1; j < Math.abs(diffs); j++){
          iw = iw.multiply(iw);
        }
        resps[i] = resps[i].multiply(iw);
      } else if (diffs > 0) { 
        // differentiation is I(w) / (-i/w)^n
        Complex iw = new Complex(0.0, -1.0 / (integConstant*deltaFrq) );
        for (int j = 1; j < Math.abs(diffs); j++){
          iw = iw.multiply(iw);
        }
        resps[i] = iw.multiply(resps[i]);
      }
      
      
      // lastly, scale by the scale we chose (gain0 or gain1*gain2)
      resps[i] = resps[i].multiply(scale);
    }
    
    return resps;
  }
  
  /**
   * Given a best-fit vector, build the poles and zeros to use the ones
   * defined by that vector. Imaginary values that are non-zero are constrained 
   * to be (implicitly) defining a complex conjugate of the pole/zero they
   * are built into. Similarly, any poles or zeros in the initial list that
   * are duplicated are all set to have the same new value. 
   * @see #zerosToVector and #polesToVector
   * @param params Array of real and imaginary component values of poles
   * and zeros
   * @param lowFreq True if the fit values are for low-frequency components
   * @param numZeros How much of the input parameter array is zero components
   * @return New InstrumentResponse with the fit values applied to it
   */
  public InstrumentResponse 
  buildResponseFromFitVector(double[] params, boolean lowFreq, 
      int numZeros) {
    
    // get all distinct pole values as lists
    List<Complex> zList = getSortedZeroKeys();
    List<Complex> pList = getSortedPoleKeys();
    
    // first covert poles and zeros back to complex values to make this easier
    List<Complex> zerosAsComplex = new ArrayList<Complex>();
    for (int i = 0; i < numZeros; i += 2) {
      Complex c = new Complex( params[i], params[i+1] );
      zerosAsComplex.add(c);
    }
    
    List<Complex> polesAsComplex = new ArrayList<Complex>();
    for (int i = numZeros; i < params.length; i += 2) {
      Complex c = new Complex( params[i], params[i+1] );
      polesAsComplex.add(c);
    }
    
    // fit the zeros
    Map<Complex, Integer> builtZeros = new HashMap<Complex, Integer>();
    
    // first, add the literally zero values; these aren't fit
    // (NOTE: we expect count to never be more than 2)
    int start;
    start = 0;
    Complex firstZero = zList.get(0);
    if (firstZero.abs() == 0.) {
      start = 1;
      int count = zeros.get(firstZero);
      builtZeros.put(firstZero, count);
    }
    
    // add the low-frequency zeros from source if they're not being fit
    if (!lowFreq) {
      // add zeros until they reach the high-freq cutoff point
      // start from current index of data
      for (int i = start; i < zList.size(); ++i) {
        Complex zero = zList.get(i);
        int count = zeros.get(zero);
        if ( zero.abs() / NumericUtils.TAU > 1. ) {
          // zeros after this point are high-frequency
          break;
        }
        builtZeros.put(zero, count);
      }
    }
    
    // now add the zeros under consideration for fit
    // these are the high-frequency zeros if we're doing high-frequency cal
    // or the low-frequency zeros otherwise
    int offset;
    offset = builtZeros.size();
    for (int i = 0; i < zerosAsComplex.size(); ++i) {
      Complex origZero = zList.get(i + offset);
      int count = zeros.get(origZero);
      Complex zero = zerosAsComplex.get(i);
      builtZeros.put(zero, count);
      
      // add conjugate if it has one
      if ( zero.getImaginary() != 0. ) {
        builtZeros.put( zero.conjugate(), count );
        ++offset; // skipping over the original conjugate pair
      }
    }
    
    // now add in all remaining zeros
    for (int i = builtZeros.size(); i < zList.size(); ++i) {
      Complex zero = zList.get(i);
      int count = zeros.get(zero);
      builtZeros.put(zero, count);
    }
    //System.out.println("builtZeros: "+builtZeros);
    
    // now do the same thing as the zeros but for the poles
    Map<Complex, Integer> builtPoles = new HashMap<Complex, Integer>();
    
    // low frequency poles not being fit added first (keeps list sorted)
    if (!lowFreq) {
      // first add low-frequency poles not getting fit by high-freq cal
      for (int i = 0; i < pList.size(); ++i) {
        Complex pole = pList.get(i);
        if ( pole.abs() / NumericUtils.TAU > 1. ) {
          break;
        }
        int count = poles.get(pole);
        builtPoles.put(pole, count);
      }
    } else if ( hasTooLowFreqPole() ) {
      // used in the odd KS54000 case, we don't fit the low-freq damping pole
      Complex pole = pList.get(0);
      int count = poles.get(pole);
      builtPoles.put(pole, count);
    }
    
    offset = builtPoles.size();
    // now add the poles under consideration for fit as with zeros
    for (int i = 0; i < polesAsComplex.size(); ++i) {
      Complex origPole = pList.get(i + offset);
      int count = poles.get(origPole);
      Complex pole = polesAsComplex.get(i);
      builtPoles.put(pole, count);
      
      // add conjugate if it has one
      if ( pole.getImaginary() != 0. ) {
        builtPoles.put( pole.conjugate(), count );
        ++offset;
      }
    }
    
    // now add the poles that remain
    for (int i = builtPoles.size(); i < pList.size(); ++i) {
      Complex pole = pList.get(i);
      int count = poles.get(pole);
      builtPoles.put(pole, count);
    }
    //System.out.println("builtPoles: "+builtPoles);
    
    // create a copy of this instrument response and set the new values
    InstrumentResponse out = new InstrumentResponse(this);
    out.setZerosMap(builtZeros);
    out.setPolesMap(builtPoles);
    return out;
    
  }
  
  private void setZerosMap(Map<Complex, Integer> newZeros) {
    zeros = newZeros;
  }

  private void setPolesMap(Map<Complex, Integer> newPoles) {
    poles = newPoles;
  }
  
  /**
   * Get the gain stages of the RESP file. Stage x is at index x. That is,
   * the sensitivity is at 0, the sensor gain is at 1, and the digitizer
   * gain is at 2.
   * @return Array of all gain stages found in resp file, including stage 0
   */
  public double[] getGain() {
    return gain;
  }
  
  /**
   * Return the name of this response file (i.e., STS-1Q330 or similar)
   * Used primarily for identifying response curve on plots
   * @return String containing ID of current response
   */
  public String getName() {
    return name;
  }
  
  /**
   * Get the normalization of the response
   * @return normalization constant
   */
  public double getNormalization() {
    return normalization;
  }
  
  /**
   * Get the normalization frequency
   * @return normalization frequency (Hz)
   */
  public double getNormalizationFrequency() {
    return normalFreq;
  }
  
  /**
   * Return the list of poles in the RESP file, not including error terms
   * @return List of complex numbers; index y is the yth pole in response list
   */
  public List<Complex> getPoles() {
    List<Complex> pList = getSortedPoleKeys();
    List<Complex> listOut = new ArrayList<Complex>();
    for ( Complex p : pList ) {
      int count = poles.get(p);
      for (int i = 0; i < count; ++i) {
        listOut.add(p);
      }
    }
    return listOut;
  }
  
  /**
   * Get the transfer function of this response file (laplacian, linear)
   * @return transfer type as an enumeration (can get factor as numeric type
   * by calling getFunction() on the returned value)
   */
  public TransferFunction getTransferFunction() {
    return transferType;
  }
  
  /**
   * Gives the unit type of the RESP file (displacement, velocity, acceleration)
   * @return Unit type as enumeration, distance measures of meters and time of
   * seconds (i.e., acceleration is m/s^2)
   */
  public Unit getUnits() {
    return unitType;
  }
  
  /**
   * Return the list of zeros in the RESP file, not including error terms
   * @return List of complex numbers; index y is the yth zero in response list
   */
  public List<Complex> getZeros() {
    List<Complex> zList = getSortedZeroKeys();
    List<Complex> listOut = new ArrayList<Complex>();
    for (Complex z : zList) {
      int count = zeros.get(z);
      for (int i = 0; i < count; ++i) {
        listOut.add(z);
      }
    }
    return listOut;
  }
  
  /**
   * Determines whether or not the first pole is too low to fit even with
   * a low-frequency random cal solver operation. Mainly an issue for
   * the KS54000 RESP, since the damping curve is unusual.
   * @return True if the initial pole is too low-frequency to add to fit.
   */
  private boolean hasTooLowFreqPole() {
    final double CUTOFF = 1. / 1000.;
    List<Complex> pList = getPoles();
    if ( ( pList.get(0).abs() / NumericUtils.TAU ) < CUTOFF ) {
      // first two poles are low-frequency
      return true;
    }
    
    return false;
  }
 
  /**
   * Return the number of epochs found in the data, for use in warning
   * if the user has loaded in a response with multiple epochs
   * @return number of times 0xb052f22 lines were found in file
   */
  public int getEpochsCounted() {
    return epochsCounted;
  }
  
  /**
   * Read in each line of a response and parse and store relevant lines
   * according to the hex value at the start of the line
   * @param br reader of a given file to be parse
   * @throws IOException if the reader cannot read the given file
   */
  private void parserDriver(BufferedReader br) throws IOException {
    
    epochsCounted = 0;
    
    numStages = 0;
    double[] gains = new double[10];
    for (int i = 0; i < gains.length; ++i) {
      gains[i] = 1;
    }
    normalization = 0;
    normalFreq = 0;
    int gainStage = -1;
    Complex[] polesArr = null;
    Complex[] zerosArr = null;
    
    String line = br.readLine();
    
    while (line != null) {
      
      if( line.length() == 0 ) {
        // empty line? need to skip it
        line = br.readLine();
        continue;
      }
      
      if (line.charAt(0) == '#') {
        // comment -- skip
        line = br.readLine();
        continue;
      } else {
        // the components of each line, assuming split by 2 or more spaces
        String[] words = line.split("\\s\\s+");
        String hexIdentifier = words[0];
        
        switch (hexIdentifier) {
        case "B052F22":
          ++epochsCounted;
          // NEW EPOCH REACHED. Clear out old data.
          numStages = 0;
          gains = new double[10];
          for (int i = 0; i < gains.length; ++i) {
            gains[i] = 1;
          }
          normalization = 0;
          normalFreq = 0;
          gainStage = -1;
          polesArr = null;
          zerosArr = null;
        case "B053F03":
          // transfer function type specified
          // first character of third component of words
          switch ( words[2].charAt(0) ) {
          case 'A':
            transferType = TransferFunction.LAPLACIAN;
            break;
          case 'B':
            transferType = TransferFunction.LINEAR;
            break;
          default:
            // defaulting to LAPLACIAN if type is different from a or b
            // which is likely to be more correct
            transferType = TransferFunction.LAPLACIAN;
          }
          break;
        case "B053F05":
          // parse the units of the transfer function (usually velocity)
          // first *word* of the third component of words
          String[] unitString = words[2].split("\\s");
          String unit = unitString[0];
          switch (unit.toLowerCase()) {
          case "m/s":
            unitType = Unit.VELOCITY;
            break;
          case "m/s**2":
            unitType = Unit.ACCELERATION;
            break;
          default:
            String e = "Unit type was given as " + unit + ".\n";
            e += "Nonstandard unit, or not a velocity or acceleration";
            throw new IOException(e);
          }
          break;
        case "B053F07":
          // this is the normalization factor A0
          // this is the entire third word of the line, as a double
          normalization = Double.parseDouble(words[2]);
          break;
        case "B053F08":
          // this is the normalization frequency
          // once again the entire third word of the line as double
          normalFreq = Double.parseDouble(words[2]);
          break;
        case "B053F09":
          // the number of zeros listed in reponse pole/zero lines
          // again, this is the entire third word, as an int
          int numZero = Integer.parseInt(words[2]);
          zerosArr = new Complex[numZero];
          break;
        case "B053F14":
          // same as above line but for the number of poles
          int numPole = Integer.parseInt(words[2]);
          polesArr = new Complex[numPole];
          break;
        case "B053F10-13":
          // these are the lists of response zeros, in order with index,
          // real (double), imaginary (double), & corresponding error terms
          parseTermAsComplex(line,zerosArr);
          break;
        case "B053F15-18":
          // as above but for poles
          parseTermAsComplex(line, polesArr);
          break;
        case "B058F03":
          // gain stage sequence number; again, full third word as int
          // this is used to map the gain value to an index
          gainStage = Integer.parseInt(words[2]);
          numStages = Math.max(numStages, gainStage);
          break;
        case "B058F04":
          // should come immediately and only after the gain sequence number
          // again, it's the third full word, this time as a double
          // map allows us to read in the stages in whatever order
          // in the event they're not sorted in the response file
          // and allows us to have basically arbitrarily many stages
          gains[gainStage] = Double.parseDouble(words[2]);
          
          // reset the stage to prevent data being overwritten
          gainStage = -1;
          break;
        }
        
        line = br.readLine();
      } // else
      
    } // end of file-read loop (EOF reached, line is null)
    
    // turn map of gain stages into list
    gain = gains;
    ++numStages; // offset by 1 to represent size of stored gain stages
    
    // turn pole/zero arrays into maps from pole values to # times repeated
    setZeros(zerosArr);
    setPoles(polesArr);
  }
  
  /**
   * Parses a response file of the sort found on the Iris Nominal Response
   * Library. These files can be found at http://ds.iris.edu/NRL/
   * This function currently does not parse a full response file, but instead
   * only examines fields relevant to self-noise calculations.
   * @param filename Full path to the response file
   */
  private void parseResponseFile(String filename) throws IOException {
    
    // response files have a very nice format that is not so nice as something
    // like JSON but still quite easy to parse
    // lines either begin with a hex value or a '#'
    // '#' marks comments while the hex value is used to interpret values
    // each line with a hex value appears to have the following format
    // [hex value] [whitespace] [name] [whitespace] [value]
    // where name is the human-readable explanation of what a value represents
    // in some cases, 'value' may not be just a raw value but also include
    // some information about the value, such as verbose unit specifications
    
    // there is one exception, the actual pole/zero fields, which have 5
    // components after the hex identifier
    
    BufferedReader br;
    try {
      br = new BufferedReader( new FileReader(filename) );
      parserDriver(br);
      br.close();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
    
  }

  /**
   * Create a RealVector of the components in the list of poles for this
   * response, used for finding best-fit poles from a random calibration
   * The complex numbers each representing a pole are split into their
   * real and imaginary components (each a double), with the real components
   * being set on even indices and imaginary components on the odd indices; 
   * each even-odd pair (i.e., 0-1, 2-3, 4-5) of values in the vector define a
   * single pole. Poles with non-zero imaginary components do not have their
   * conjugate included in this vector in order to maintain constraints. 
   * @param lowFreq True if the low-frequency poles are to be fit
   * @param nyquist Nyquist rate of data (upper bound on high-freq poles to fit)
   * @return RealVector with fittable pole values
   */
  public RealVector polesToVector(boolean lowFreq, double nyquist) {
    // first, sort poles by magnitude
    List<Complex> pList = getSortedPoleKeys();
    
    double peak = PEAK_MULTIPLIER * nyquist;
    
    
    // create a list of doubles that are the non-conjugate elements from list
    // of poles, to convert to array and then vector format
    List<Double> componentList = new ArrayList<Double>();
    
    // starting index for poles, shift up one if lowest-freq pole is TOO low
    int start = 0;
    if ( hasTooLowFreqPole() ) {
      start = 1;
    }
    
    for (int i = start; i < pList.size(); ++i) {
      
      double frq = pList.get(i).abs() / NumericUtils.TAU;
      if ( !lowFreq && (frq < 1.) ) {
        // don't include poles below 1Hz in high-frequency calibration
        continue;
      }
      if ( lowFreq && (frq > 1.) ) {
        // only do low frequency calibrations on poles up to 
        break;
      }
      if ( !lowFreq && (frq >= peak) ) {
        // don't fit poles above fraction of nyquist rate of sensor output
        break;
      }
      
      // a complex is just two doubles representing real and imaginary lengths
      double realPart = pList.get(i).getReal();
      double imagPart = pList.get(i).getImaginary();
      
      componentList.add(realPart);
      componentList.add(imagPart);
      
      if (imagPart != 0.) {
        ++i; // skip complex conjuage
      }
      
    }
    
    // turn into array to be turned into vector
    // can't use toArray because List doesn't use primitive double objects
    double[] responseVariables = new double[componentList.size()];
    for (int i = 0; i < responseVariables.length; ++i) {
      responseVariables[i] = componentList.get(i);
    }
    
    return MatrixUtils.createRealVector(responseVariables);
  }
  
  /**
   * Set name of response file, used in some plot and report generation
   * @param newName New name to give this response
   */
  public void setName(String newName) {
    name = newName;
  }
  
  /**
   * Replace the current poles of this response with new ones
   * @param poleList New poles to replace the current response poles with
   */
  public void setPoles(List<Complex> poleList) {
    poles = new HashMap<Complex, Integer>();
    for (Complex p : poleList) {
      if ( poles.keySet().contains(p) ) {
        int count = zeros.get(p) + 1;
        poles.put(p, count);
      } else {
        poles.put(p, 1);
      }
    }
  }
  
  public void setPoles(Complex[] poleList) {
    poles = new HashMap<Complex, Integer>();
    for (Complex p : poleList) {
      if ( poles.keySet().contains(p) ) {
        int count = poles.get(p) + 1;
        poles.put(p, count);
      } else {
        poles.put(p, 1);
      }
    }
  }

  /**
   * Set the list of zeros to a new list, such as after fitting from random cal
   * @param newZeros New list of zeros to assign this calibration
   */
  public void setZeros(List<Complex> zeroList) {
    zeros = new HashMap<Complex, Integer>();
    for (Complex z : zeroList) {
      if ( zeros.keySet().contains(z) ) {
        int count = zeros.get(z) + 1;
        zeros.put(z, count);
      } else {
        zeros.put(z, 1);
      }
    }
  }
  
  public void setZeros(Complex[] zeroList) {
    zeros = new HashMap<Complex, Integer>();
    for (Complex z : zeroList) {
      if ( zeros.keySet().contains(z) ) {
        int count = zeros.get(z) + 1;
        zeros.put(z, count);
      } else {
        zeros.put(z, 1);
      }
    }
  }
  
  /**
   * Output text report of this response file. Not same format as IRIS RESP.
   */
  public String toString() {
    StringBuilder sb = new StringBuilder();
    
    NumberFormat nf = NumberFormat.getInstance();
    nf.setMaximumFractionDigits(4);
    ComplexFormat cf = new ComplexFormat(nf);
    
    sb.append("Response name: ");
    sb.append(name);
    sb.append('\n');
    sb.append("Gain stage values: ");
    sb.append('\n');
    
    for (int i = 0; i < numStages; ++i) {
      sb.append(i);
      sb.append(": ");
      sb.append( nf.format(gain[i]) );
      sb.append("\n");
    }
    
    sb.append("Normalization: ");
    sb.append(normalization);
    sb.append('\n');
    sb.append("Normalization frequency (Hz): ");
    sb.append(normalFreq);
    sb.append('\n');
    
    sb.append("Transfer function ");
    if (transferType == TransferFunction.LAPLACIAN) {
      sb.append("is LAPLACIAN");
    } else {
      sb.append("is LINEAR");
    }
    sb.append('\n');
    
    sb.append("Response input units: ");
    if (unitType == Unit.DISPLACEMENT) {
      sb.append("displacement (m)");
    } else if (unitType == Unit.VELOCITY) {
      sb.append("velocity (m/s)");
    } else if (unitType == Unit.ACCELERATION) {
      sb.append("acceleration (m/s^2)");
    }
    sb.append('\n');
    
    sb.append("Response zeros: ");
    sb.append('\n');
    List<Complex> zList = getZeros();
    for (int i = 0; i < zList.size(); ++i) {
      sb.append(i);
      sb.append(": ");
      sb.append( cf.format( zList.get(i) ) );
      sb.append("\n");
    }
    
    sb.append("Response poles: ");
    sb.append('\n');
    List<Complex> pList = getPoles();
    for (int i = 0; i < pList.size(); ++i) {
      sb.append(i);
      sb.append(": ");
      sb.append( cf.format( pList.get(i) ) );
      sb.append("\n");
    }
    
    return sb.toString();
  }

  /**
   * Create a RealVector of the components in the list of zeros for this
   * response, used for finding best-fit zeros from a random calibration
   * The complex numbers each representing a zero are split into their
   * real and imaginary components (each a double), with the real components
   * being set on even indices and imaginary components on the odd indices; 
   * each even-odd pair (i.e., 0-1, 2-3, 4-5) of values in the vector define a
   * single zero. Zeros with non-zero imaginary components do not have their
   * conjugate included in this vector in order to maintain constraints. 
   * @param lowFreq True if the low-frequency zeros are to be fit
   * @param nyquist Nyquist rate of data (upper bound on high-freq zeros to fit)
   * @return RealVector with fittable zero values
   */
  public RealVector zerosToVector(boolean lowFreq, double nyquist) {
    List<Complex> zList = getSortedZeroKeys();
    
    double peak = PEAK_MULTIPLIER * nyquist;
    
    // create a list of doubles that are the non-conjugate elements from list
    // of poles, to convert to array and then vector format
    List<Double> componentList = new ArrayList<Double>();
    
    for (int i = 0; i < zeros.size(); ++i) {
      
      if ( zList.get(i).abs() == 0. ) {
        // ignore zeros that are literally zero-valued
        continue;
      }
      
      double cutoffChecker = zList.get(i).abs() / NumericUtils.TAU;
      
      if ( lowFreq && (cutoffChecker > 1.) ) {
        // only do low frequency calibrations on zeros up to 1Hz
        break;
      }
      if ( !lowFreq && (cutoffChecker < 1.) ) {
        // don't include zeros > 1Hz in high-frequency calibration
        continue;
      }
      if ( !lowFreq && (cutoffChecker > peak) ) {
        // don't fit zeros above 80% nyquist rate of sensor output
        break;
      }
      
      double realPart = zList.get(i).getReal();
      double imagPart = zList.get(i).getImaginary();
      componentList.add(realPart);
      componentList.add(imagPart);
      
      if (imagPart != 0.) {
        ++i;
      }
      
    }

    // turn into array to be turned into vector
    // can't use toArray because List doesn't use primitive double objects
    double[] responseVariables = new double[componentList.size()];
    for (int i = 0; i < responseVariables.length; ++i) {
      responseVariables[i] = componentList.get(i);
    }
    
    return MatrixUtils.createRealVector(responseVariables);
  }
  
}


