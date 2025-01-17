package asl.sensor.experiment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresOptimizer;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresProblem;
import 
org.apache.commons.math3.fitting.leastsquares.MultivariateJacobianFunction;
import org.apache.commons.math3.fitting.leastsquares.ParameterValidator;
import org.apache.commons.math3.linear.DiagonalMatrix;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.util.Pair;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import asl.sensor.input.DataBlock;
import asl.sensor.input.DataStore;
import asl.sensor.input.InstrumentResponse;
import asl.sensor.utils.FFTResult;
import asl.sensor.utils.LiterallyJustTheCommonsLMClass;
import asl.sensor.utils.NumericUtils;

/**
 * This experiment takes in a randomized calibration signal and the
 * corresponding output from a seismic sensor. It calculates the implicit
 * response by deconvolving the calibration signal from the sensor output, and
 * then finds the best-fit poles (lowest 2 for low-frequency calibrations,
 * all remaining poles for high-frequency calibrations) to
 * match the magnitude and rotation angle of the calculated response curve
 * produced from the deconvolution.
 * Plottable data includes the sensor's response curve
 * (Bode plot), the calculated response from deconvolution, and the plot
 * of the response from the best-fit parameters.
 * These plots are returned in a list of two datasets: the first holds
 * the absolute values per-frequency of those curves, and the second holds
 * the angle of each such point in complex space.
 * For more details on the algorithm, see Ringler, Hutt, et al., 
 * "Estimating Pole-Zero Errors in GSN-IRIS/USGS Network Calibration Metadata", 
 * Bulletin of the Seismological Society of America, Vol 102 (Apr. 2012).
 * @author akearns
 *
 */
public class RandomizedExperiment 
extends Experiment implements ParameterValidator {

  private static final double DELTA = 1E-12;
  public static final double PEAK_MULTIPLIER = 0.4;
      //NumericUtils.PEAK_MULTIPLIER; // max pole-fit frequency
      // NumericUtils.PEAK_MULTIPLIER 
  
  public static final boolean PRINT_EVERYTHING = true;
  // bool logic used so that if PRINT_EVERYTHING is false, this won't work
  public static final boolean OUTPUT_TO_TERMINAL = PRINT_EVERYTHING && true;
  
  // To whomever has to maintain this code after I'm gone:
  // I'm sorry, I'm so so sorry
  // I suppose it's a little neater now that some functions are part of the
  // response class? It's still inherently nasty due to issues relating to
  // converting complex lists into arrays of doubles in order to use the solver
  
  private double initialResidual, fitResidual;
  private List<Complex> initialPoles;
  private List<Complex> fitPoles;
  private List<Complex> initialZeros;
  private List<Complex> fitZeros;
  
  private List<String> inputsPerCalculation;
  private List<String> outputsPerCalculation;
  
  // when true, doesn't run solver, in event parameters have an issue
  // (does the solver seem to have frozen? try rebuilding with this as true,
  // and then run the plot -- show nominal resp. and estimated curves)
  public final boolean SKIP_SOLVING = false;
  
  private boolean lowFreq; // fit the low- or high-frequency poles?
  
  private InstrumentResponse fitResponse;
  
  private double[] freqs, observedResult, weights;
  private double nyquist;
  
  private boolean freqSpace;
  
  private double maxMagWeight, maxArgWeight; // max values of magnitude, phase
  
  private int normalIdx; // location of value to set to 0 in curves for scaling
  private int numZeros; // how many entries in parameter vector define zeros
  private int numIterations; // how much the solver ran
  
  public RandomizedExperiment() {
    super();
    lowFreq = false;
    normalIdx = 0;
    numIterations = 0;
    freqSpace = true;
  }
  
  /*
   * (non-Javadoc)
   * BACKEND FUNCTION BEGINS HERE
   * @see asl.sensor.experiment.Experiment#backend(asl.sensor.input.DataStore)
   */
  @Override
  protected void backend(DataStore ds) {
    
    boolean dontSolve = getSolverState(); // true if we should NOT run solver
    
    inputsPerCalculation = new ArrayList<String>();
    outputsPerCalculation = new ArrayList<String>();
    
    normalIdx = 1;
    numIterations = 0;
    
    // construct response plot
    DataBlock calib = ds.getBlock(0);
    
    /*
    if ( ds.getBlock(sensorOutIdx).getName().equals( calib.getName() ) ) {
      sensorOutIdx = ds.getXthFullyLoadedIndex(2);
    }
    */

    DataBlock sensorOut = ds.getBlock(1);
    fitResponse = new InstrumentResponse( ds.getResponse(1) );
    
    // System.out.println(calib.size() + ", " + sensorOut.size());
    
    dataNames.add( calib.getName() );
    dataNames.add( sensorOut.getName() );
    dataNames.add( fitResponse.getName() );
    
    InstrumentResponse initResponse = new InstrumentResponse(fitResponse);
    initialPoles = new ArrayList<Complex>( fitResponse.getPoles() );
    initialZeros = new ArrayList<Complex>( fitResponse.getZeros() );
    
    // get the plots of the calculated response from deconvolution
    // PSD(out, in) / PSD(in, in) gives us PSD(out) / PSD(in) while removing
    // imaginary terms from the denominator due to multiplication with the
    // complex conjugate
    // PSD(out) / PSD(in) is the response curve (i.e., deconvolution)
    // also, use those frequencies to get the applied response to input
    FFTResult numeratorPSD, denominatorPSD;
    numeratorPSD = FFTResult.spectralCalc(sensorOut, calib);
    denominatorPSD = FFTResult.spectralCalc(calib, calib);
    
    freqs = numeratorPSD.getFreqs(); // should be same for both results
    
    // store nyquist rate of data because freqs will be trimmed down later
    nyquist = sensorOut.getSampleRate() / 2.;
    
    // trim frequency window in order to restrict range of response fits
    double minFreq, maxFreq, extFreq;
    // extfreq is how far out to extend data past range of fit
    // low frequency cal fits over a different range of data
    if (lowFreq) {
      minFreq = 0.001; // 1000s period
      maxFreq = 0.05; // 20s period
      extFreq = maxFreq;
    } else {
      minFreq = .2; // lower bound of .2 Hz (5s period) due to noise
      // get factor of nyquist rate, again due to noise
      maxFreq = PEAK_MULTIPLIER * nyquist;
      extFreq = InstrumentResponse.PEAK_MULTIPLIER * nyquist;
    }
    
    // now trim frequencies to in range
    int startIdx = -1; int endIdx = -1; int extIdx = -1;
    for (int i = 0; i < freqs.length; ++i) {
      
      if (freqs[i] < minFreq) {
        continue;
      } else if (startIdx < 0) {
        startIdx = i;
      }
      if (freqs[i] > maxFreq) {
        if (endIdx < 0) {
          endIdx = i;
        }
        if (freqs[i] > extFreq) {
          extIdx = i;
          break;
        }
      }
    }
    
    double zeroTarget; // frequency to set all curves to zero at
    if (lowFreq) {
      zeroTarget = 0.02;
    } else {
      zeroTarget = 1.0;
    }
    
    // Collections.sort(freqList); // done mostly for peace of mind
    double[] freqsFull = Arrays.copyOfRange(freqs, startIdx, extIdx);
    freqs = Arrays.copyOfRange(freqs, startIdx, endIdx);
    
    // trim the PSDs to the data in the trimmed frequency range
    // System.out.println("INDICES: " + startIdx + "," + endIdx);
    Complex[] numeratorPSDVals = 
        Arrays.copyOfRange(numeratorPSD.getFFT(), startIdx, extIdx);
    Complex[] denominatorPSDVals = 
        Arrays.copyOfRange(denominatorPSD.getFFT(), startIdx, extIdx);
    
    for (int i = 0; i < freqs.length; ++i) {
      if ( freqs[i] == zeroTarget || ( i > 0 &&
          (freqs[i] > zeroTarget && freqs[i - 1] < zeroTarget) ) ) {
        normalIdx = i;
      }
    }
    
    // calculated response from deconvolving calibration from signal
    // (this will be in displacement and need to be integrated)
    Complex[] plottedResponse = new Complex[freqsFull.length];
    for (int i = 0; i < plottedResponse.length; ++i) {
      Complex numer = numeratorPSDVals[i];
      double denom = denominatorPSDVals[i].abs(); // phase is 0
      plottedResponse[i] = numer.divide(denom);
      // convert from displacement to velocity
      Complex scaleFactor = new Complex(0., NumericUtils.TAU * freqsFull[i]);
      plottedResponse[i] = plottedResponse[i].multiply(scaleFactor);
    }
    //
    // 3-point moving average
    plottedResponse = NumericUtils.multipointMovingAverage(plottedResponse, 5);
    // the range over the fit is trimmed from the full plot
    // (i.e., we may fit up to 50% of nyquist but display up to 80% in HF cals)
    Complex[] estResponse = 
        Arrays.copyOfRange(plottedResponse, 0, freqs.length);
    
    // next, normalize estimated response
    String name = sensorOut.getName();
    XYSeries calcMag = new XYSeries("Calc. resp. (" + name + ") magnitude");
    XYSeries calcArg = new XYSeries("Calc. resp. (" + name + ") phase");
    
    // scaling values, used to set curve values to 0 at 1Hz
    Complex scaleValue = estResponse[normalIdx];
    double subtractBy = 20 * Math.log10( scaleValue.abs() );
    double rotateBy = NumericUtils.atanc(scaleValue);
    
    // data to fit poles to; first half of data is magnitudes of resp (dB)
    // second half of data is angles of resp (radians, scaled)
    observedResult = new double[2 * estResponse.length];
    
    // prevent discontinuities in angle plots
    double phiPrev = 0.;
    
    double[] obsdAmps = new double[estResponse.length];
    
    for (int i = 0; i < estResponse.length; ++i) {
      int argIdx = estResponse.length + i;
      
      Complex estValue = estResponse[i];
      // estValue = estValue.subtract(scaleValue);
      double estValMag = estValue.abs();
      double phi = NumericUtils.atanc(estValue);
      phi -= rotateBy;
      
      phi = NumericUtils.unwrap(phi, phiPrev);
      // iterative step
      phiPrev = phi;
      phi = Math.toDegrees(phi);
      
      if ( Double.isNaN(estValMag) ) {
        observedResult[i] = 0;
        observedResult[argIdx] = 0;
      } else {
        obsdAmps[i] = estValMag / scaleValue.abs();
        observedResult[i] = 20 * Math.log10(estValMag);
        observedResult[i] -= subtractBy;
        
        double argument = phi;
        // argument /= rotateBy;
        // argument *= -1;
        observedResult[argIdx] = argument;
      }
      
      double xAxis;
      if (freqSpace) {
        xAxis = freqs[i];
      } else {
        xAxis = 1. / freqs[i];
      }
      calcMag.add(xAxis, observedResult[i]);
      calcArg.add(xAxis, observedResult[argIdx]);
    }
    
    for (int i = estResponse.length; i < plottedResponse.length; ++i) {
      Complex estValue = plottedResponse[i];
      // estValue = estValue.subtract(scaleValue);
      double estValMag = estValue.abs();
      double phi = NumericUtils.atanc(estValue);
      phi -= rotateBy;
      
      phi = NumericUtils.unwrap(phi, phiPrev);
      // iterative step
      phiPrev = phi;
      phi = Math.toDegrees(phi);
      
      double argument;
      if ( Double.isNaN(estValMag) ) {
        estValMag = 0;
        argument = 0;
      } else {
        //estValMag /= scaleValue.abs();
        estValMag = 20 * Math.log10(estValMag);
        estValMag -= subtractBy;
        argument = phi;
      }
      
      double xAxis;
      if (freqSpace) {
        xAxis = freqsFull[i];
      } else {
        xAxis = 1. / freqsFull[i];
      }
      calcMag.add(xAxis, estValMag);
      calcArg.add(xAxis, argument);
    }
    
    // want to set up weight-scaling for the input so rotation doesn't dominate
    // solver's residual calculations, i.e., so no phase overfitting
    
    fireStateChange("Getting weighting....");
    
// we may want to set this to be the absolute value.
    maxArgWeight = 1.; maxMagWeight = 0.;
    Complex weightScaler = estResponse[normalIdx];
    double subtractWeight = 20 * Math.log10( weightScaler.abs() );
    double rotateWeight = NumericUtils.atanc(weightScaler);
    for (int i = 0; i < estResponse.length; ++i) {
      // int argIdx = i + appResponse.length;
      double magCandidate = 20 * Math.log10( estResponse[i].abs() );
      magCandidate -= subtractWeight;
      double phiCandidate = Math.abs( NumericUtils.atanc(estResponse[i]) );
      phiCandidate -= rotateWeight;
      if ( magCandidate > maxMagWeight ) {
        maxMagWeight = magCandidate;
      }
      if ( phiCandidate > maxArgWeight ) {
        maxArgWeight = phiCandidate;
      }
    }
    
    fireStateChange("Setting weight matrix...");
    // System.out.println(maxMagWeight);
    
    // we have the candidate mag and phase, now to turn them into weight values
    //maxMagWeight = 1000. / maxMagWeight; // scale factor to weight over
    // This is the B. scaling factor from the email sent 11/01 titled STS-6 response
    // we have the candidate mag and phase, now to turn them into weight values
    // find the relative magnitude of the phase and amplitude and create a scaling factor
    // based on that.
    boolean Weight = false;
    if (Weight) {
       double maxYvalue=Math.abs(calcMag.getMaxY());
       double minYvalue=Math.abs(calcMag.getMinY());
       double absMaxYvalue = Math.max(maxYvalue,minYvalue);
       double powerOfMag = Math.floor(Math.log10(absMaxYvalue));
       double maxYArgvalue=Math.abs(calcArg.getMaxY());
       double minYArgvalue=Math.abs(calcArg.getMinY());
       double absMaxYArgvalue = Math.max(maxYArgvalue,minYArgvalue);
       double powerOfArg = Math.floor(Math.log10(absMaxYArgvalue));
       double magEqualizer= Math.pow(10.,powerOfMag+powerOfArg+1);
       System.out.println("how does this look: "+ magEqualizer); //check value
       maxMagWeight = magEqualizer / maxMagWeight; // scale factor to weight over
    } else {
       maxMagWeight=1000./maxMagWeight;
    }      

    if (maxArgWeight != 0.) {
      maxArgWeight = 1./ maxArgWeight;
    }
    
    // weight matrix
    weights = new double[observedResult.length];
    for (int i = 0; i < estResponse.length; ++i) {
      int argIdx = i + estResponse.length;
      double denom;
      if (!lowFreq) {
        if (freqs[i] < 1) {
          denom = 1; // weight everything up to 1Hz equally
        } else {
          denom = freqs[i]; // set everything (else) to 1/f weighting
        }
      } else {
        if (freqs[i] < .01) {
          denom = freqs[i];
        } else {
          denom = .01;
        }
      }
      weights[argIdx] = maxArgWeight / denom;
      weights[i] = maxMagWeight / denom;
    }
    
    DiagonalMatrix weightMat = new DiagonalMatrix(weights);
    
    fireStateChange("Getting estimate and setting up solver...");
    
    // now to set up a solver for the params -- first, get the input variables
    // complex values are technically two variables, each a double
    // so, let's split up their real and im components and make each one a
    // variable. (we also need to ignore conjugate values, for constraints)
    RealVector initialGuess, initialPoleGuess, initialZeroGuess;
    
    initialPoleGuess = fitResponse.polesToVector(lowFreq, nyquist);
    initialZeroGuess = fitResponse.zerosToVector(lowFreq, nyquist);
    numZeros = initialZeroGuess.getDimension();
    initialGuess = initialZeroGuess.append(initialPoleGuess);
    
    if (OUTPUT_TO_TERMINAL) {
      System.out.println( "observed Result: "+Arrays.toString(observedResult) );
    }
    
    // now, solve for the response that gets us the best-fit response curve
    // RealVector initialGuess = MatrixUtils.createRealVector(responseVariables);
    RealVector obsResVector = MatrixUtils.createRealVector(observedResult);
    
    MultivariateJacobianFunction jacobian = new MultivariateJacobianFunction() {
      
      public Pair<RealVector, RealMatrix> value(final RealVector point) {
        ++numIterations;
        fireStateChange("Fitting, iteration count " + numIterations);
        Pair<RealVector, RealMatrix> pair = 
            jacobian(point);
        return pair;
      }
      
    };
    
    double costTolerance = 1.0E-15; 
    double paramTolerance = 1.0E-10;
    // probably acceptable tolerance for clean low-frequency cals BUT
    // high frequency cals are noisy and slow to converge
    // so this branch is to enable using higher tolerance to deal with that
    if (!lowFreq) {
      costTolerance = 1.0E-15;
      paramTolerance = 1.0E-10;
    }
    
    LeastSquaresOptimizer optimizer = new LiterallyJustTheCommonsLMClass().
        withCostRelativeTolerance(costTolerance).
        withParameterRelativeTolerance(paramTolerance);
    
    // set up structures that will hold the initial and final response plots
    name = fitResponse.getName();
    XYSeries initMag = new XYSeries("Initial param (" + name + ") magnitude");
    XYSeries initArg = new XYSeries("Initial param (" + name + ") phase");
    
    XYSeries fitMag = new XYSeries("Fit resp. magnitude");
    XYSeries fitArg = new XYSeries("Fit resp. phase");
    
    LeastSquaresProblem lsp = new LeastSquaresBuilder().
        start(initialGuess).
        target(obsResVector).
        model(jacobian).
        weight(weightMat).
        parameterValidator(this).
        lazyEvaluation(false).
        maxEvaluations(Integer.MAX_VALUE).
        maxIterations(Integer.MAX_VALUE).
        build();
    
    fireStateChange("Built least-squares problem; evaluating intial guess...");

    // residuals used to determine quality of solution convergence
    
    LeastSquaresProblem.Evaluation initEval = lsp.evaluate(initialGuess);
    initialResidual = initEval.getCost();
    
    fireStateChange("Got initial evaluation; running solver...");
    
    double[] initialValues =
        jacobian.value(initialGuess).getFirst().toArray();
    
    RealVector finalResultVector;
    
    if (!dontSolve) {
      LeastSquaresOptimizer.Optimum optimum = optimizer.optimize(lsp);
      finalResultVector = optimum.getPoint();
      numIterations = optimum.getIterations();
    } else {
      finalResultVector = initialGuess;
    }
    
    LeastSquaresProblem.Evaluation optimum = lsp.evaluate(finalResultVector);
    fitResidual = optimum.getCost();
    double[] fitParams = optimum.getPoint().toArray();
    // get results from evaluating the function at the two points
    double[] fitValues = 
        jacobian.value( optimum.getPoint() ).getFirst().toArray();
    
    XYSeries initResidMag = new XYSeries("Percent error of init. amplitude");
    XYSeries initResidPhase = new XYSeries("Diff. with init phase");
    XYSeries fitResidMag = new XYSeries("Percent error of fit amplitude");
    XYSeries fitResidPhase = new XYSeries("Diff with fit phase");
    
    // InstrumentResponse init = ds.getResponse(sensorOutIdx);
    
    fitResponse = fitResponse.buildResponseFromFitVector(
        fitParams, lowFreq, numZeros);
    fitPoles = fitResponse.getPoles();
    fitZeros = fitResponse.getZeros();
    
    fireStateChange("Getting extended resp curves for high-freq plots...");
    //freqs = freqsFull;
    Complex[] init = initResponse.applyResponseToInput(freqsFull);
    Complex[] fit = fitResponse.applyResponseToInput(freqsFull);
    initialValues = new double[freqsFull.length * 2];
    fitValues = new double[freqsFull.length * 2];
    for (int i = 0; i < freqsFull.length; ++i) {
      int argIdx = freqsFull.length + i;
      initialValues[i] = init[i].abs();
      initialValues[argIdx] = NumericUtils.atanc(init[i]);
      fitValues[i] = fit[i].abs();
      fitValues[argIdx] = NumericUtils.atanc(fit[i]);
    }
    fireStateChange("Scaling extended resps...");
    scaleValues(initialValues);
    scaleValues(fitValues);

    fireStateChange("Compiling data into plots...");
    
    for (int i = 0; i < freqsFull.length; ++i) {
      double xValue;
      if (freqSpace) {
        xValue = freqsFull[i];
      } else {
        xValue = 1. / freqsFull[i];
      }
      
      int argIdx = initialValues.length / 2 + i;
      initMag.add(xValue, initialValues[i]);
      initArg.add(xValue, initialValues[argIdx]);
      fitMag.add(xValue, fitValues[i]);
      fitArg.add(xValue, fitValues[argIdx]);
      
      if (i < obsdAmps.length) {
        double initAmpNumer = Math.pow(10, initialValues[i]/10);
        double fitAmpNumer = Math.pow(10, fitValues[i]/10); 
        
        double obsAmpDbl = obsdAmps[i];
        if (obsAmpDbl == 0.) {
          obsAmpDbl = Double.MIN_VALUE;
        }
        
        double errInitMag = 100. * (initAmpNumer - obsAmpDbl) / obsAmpDbl; 
        double errFitMag = 100. * (fitAmpNumer - obsAmpDbl) / obsAmpDbl; 
        initResidMag.add(xValue, errInitMag);
        fitResidMag.add(xValue, errFitMag);
        
        int obsArgIdx = observedResult.length / 2 + i;
        double observedPhase = observedResult[obsArgIdx];
        initResidPhase.add(xValue, initialValues[argIdx] - observedPhase);
        fitResidPhase.add(xValue, fitValues[argIdx] - observedPhase);
      }
    }
    
    XYSeriesCollection xysc = new XYSeriesCollection();
    xysc.addSeries(initMag);
    xysc.addSeries(calcMag);
    if (!dontSolve) {
      xysc.addSeries(fitMag);
    }

    xySeriesData.add(xysc);
    
    xysc = new XYSeriesCollection();
    xysc.addSeries(initArg);
    xysc.addSeries(calcArg);    
    if (!dontSolve) {
      xysc.addSeries(fitArg);
    }
    xySeriesData.add(xysc);
    
    xysc = new XYSeriesCollection();
    xysc.addSeries(initResidMag);
    if (!dontSolve) {
      xysc.addSeries(fitResidMag);
    }
    xySeriesData.add(xysc);
    
    xysc = new XYSeriesCollection();    
    xysc.addSeries(initResidPhase);
    if (!dontSolve) {
      xysc.addSeries(fitResidPhase);
    }
    xySeriesData.add(xysc);

  }
  
  @Override
  public int blocksNeeded() {
    return 2;
  }
  
  public double getMaxFitFrequency() {
    return freqs[freqs.length - 1];
  }

  private void scaleValues(double[] unrot) {
    int argStart = unrot.length / 2;
    double unrotScaleAmp = 20 * Math.log10(unrot[normalIdx]);
    double unrotScaleArg = unrot[argStart + normalIdx];
    double phiPrev = 0;
    if (lowFreq) {
      phiPrev = unrot[3*unrot.length/4];
    }
    for (int i = 0; i < argStart; ++i) {
      int argIdx = argStart + i;
      double db = 20 * Math.log10(unrot[i]);
      unrot[i] = db - unrotScaleAmp;
      double phi = unrot[argIdx] - unrotScaleArg;
      phi = NumericUtils.unwrap(phi, phiPrev);
      phiPrev = phi;
      unrot[argIdx] = Math.toDegrees(phi);
    }
  }
  
  
  /**
   * Backend function to set instrument response according to current
   * test variables (for best-fit calculation / backward difference) and
   * produce a response from that result. The passed response is copied on
   * start and is not modified directly. Which values (poles) are modified
   * depends on high or low frequency calibration setting.
   * @param variables values to set the instrument response to 
   * @return Doubles representing new response curve evaluation
   */
  private double[] evaluateResponse(double[] variables) {
    
    InstrumentResponse testResp = new InstrumentResponse(fitResponse);
    
    // prevent terrible case where, say, only high-freq poles above nyquist rate
    if ( variables.length > 0) {
      testResp = fitResponse.buildResponseFromFitVector(
          variables, lowFreq, numZeros);
    } else {
      System.out.println("NO VARIABLES TO SET. THIS IS AN ERROR.");
    }
    
    Complex[] appliedCurve = testResp.applyResponseToInput(freqs);
    double[] curValue = new double[freqs.length * 2];
    
    for (int i = 0; i < freqs.length; ++i) {
      int argIdx = freqs.length + i;
      Complex c = appliedCurve[i];
      curValue[i] = c.abs();
      curValue[argIdx] = NumericUtils.atanc(c);
    }
    
    scaleValues(curValue);
    
    return curValue;
  }
  
  /**
   * Get the poles that the solver has found to best-fit the est. response
   * @return new poles that should improve fit over inputted response, as a list
   */
  public List<Complex> getFitPoles() {
    List<Complex> polesOut = new ArrayList<Complex>();
    Set<Complex> retain = new HashSet<Complex>(fitPoles);
    retain.removeAll(initialPoles);
    for (Complex c : fitPoles) {
      if ( retain.contains(c) ) {
        polesOut.add(c);
      }
    }
    NumericUtils.complexRealsFirstSorter(polesOut);
    return polesOut;
  }
  
  /**
   * Get the residual value from the solved response parameters
   * @return the residual of the solved-for poles (best-fit response)
   */
  public double getFitResidual() {
    return fitResidual;
  }
  
  /**
   * Provides a handle to the fit response object for parameter output
   * @return the best-fit response
   */
  public InstrumentResponse getFitResponse() {
    return fitResponse;
  }
  
  /**
   * Get the zeros fitted from the experiment
   * @return List of zeros (complex numbers) that are used in best-fit curve
   */
  public List<Complex> getFitZeros() {
    List<Complex> zerosOut = new ArrayList<Complex>();
    Set<Complex> retain = new HashSet<Complex>(fitZeros);
    retain.removeAll(initialZeros);
    for (Complex c : fitZeros) {
      if ( retain.contains(c) ) {
        zerosOut.add(c);
      }
    }
    NumericUtils.complexRealsFirstSorter(zerosOut);
    return zerosOut;
  }
  
  public List<String> getInputsToPrint() {
    return inputsPerCalculation;
  }
  
  public List<String> getOutputsToPrint() {
    return outputsPerCalculation;
  }
  
  /**
   * Get poles used in input response, for reference against best-fit poles 
   * @return poles taken from initial response file
   */
  public List<Complex> getInitialPoles() {
    List<Complex> polesOut = new ArrayList<Complex>();
    Set<Complex> retain = new HashSet<Complex>(initialPoles);
    retain.removeAll(fitPoles);
    for (Complex c : initialPoles) {
      if ( retain.contains(c) ) {
        polesOut.add(c);
      }
    }
    NumericUtils.complexRealsFirstSorter(polesOut);
    return polesOut;
  }
  
  /**
   * Get initial zeros from (nominal) response file used as input
   * @return zeros taken from initial response file
   */
  public List<Complex> getInitialZeros() {
    List<Complex> zerosOut = new ArrayList<Complex>();
    Set<Complex> retain = new HashSet<Complex>(initialZeros);
    retain.removeAll(fitZeros);
    for (Complex c : initialZeros) {
      if ( retain.contains(c) ) {
        zerosOut.add(c);
      }
    }
    NumericUtils.complexRealsFirstSorter(zerosOut);
    return zerosOut;
  }
  
  /**
   * Get the residual value of the initial response parameters
   * @return the residual of the initial poles from fed-in response
   */
  public double getInitResidual() {
    return initialResidual;
  }
  
  /**
   * Get the number of times the algorithm iterated to produce the optimum
   * response fit, from the underlying least squares solver
   * @return the number of iterations
   */
  public int getIterations() {
    return numIterations;
  }
  
  /**
   * Used to determine whether to run the solver or not; disabling the solver
   * is useful for determining the quality of a given calibration function
   * @return True if the solver is to be run
   */
  public boolean getSolverState() {
    return SKIP_SOLVING;
  }

  /**
   * Get the values used to weight the residual calculation function.
   * The first value is the magnitude weighting, the second is phase.
   * @return Weighting values for least-squared error terms of
   */
  public double[] getWeights() {
    return new double[]{maxMagWeight, maxArgWeight};
  }
  
  /**
   * Get the range of frequencies over which the data was plotted
   * @return list of frequencies, sorted order
   */
  public double[] getFreqList() {
    return freqs;
  }
  
  /**
   * Return a 2D array containing the values of the y-axis of each of the 
   * plotted curves. The order is {input resp, calc resp, fit resp}.
   * The second index is the y-values ordered by corresponding freq
   * @return 2D array with each of the amplitude response curves
   */
  public double[][] getAmplitudesAsArrays() {
    XYSeriesCollection mags = xySeriesData.get(0);
    double[][] out = new double[mags.getSeriesCount()][];
    for (int i = 0; i < out.length; ++i) {
      XYSeries xys = mags.getSeries(i);
      out[i] = xys.toArray()[1];
    }
    return out;
  }
  
  /**
   * Return a 2D array containing the values of the y-axis of each of the 
   * plotted curves. The order is {input resp, calc resp, fit resp}.
   * The second index is the y-values ordered by corresponding freq
   * @return 2D array with each of the phase response curves
   */
  public double[][] getPhasesAsArrays() {
    XYSeriesCollection phases = xySeriesData.get(0);
    double[][] out = new double[phases.getSeriesCount()][];
    for (int i = 0; i < out.length; ++i) {
      XYSeries xys = phases.getSeries(i);
      out[i] = xys.toArray()[1];
    }
    return out;
  }
  
  @Override
  public boolean hasEnoughData(DataStore ds) {
    return ( ds.blockIsSet(0) && ds.bothComponentsSet(1) );
  }
  
  /**
   * Function to run evaluation and backward difference for Jacobian 
   * approximation given a set of points to set as response. 
   * Mainly a wrapper for the evaluateResponse function.
   * @param variables Values to set the response's poles to
   * @return RealVector with evaluation at current response value and 
   * RealMatrix with backward difference of that response (Jacobian)
   */
  private Pair<RealVector, RealMatrix> 
  jacobian(RealVector variables) {
    
    // variables = validate(variables);
    
    int numVars = variables.getDimension();
    
    double[] currentVars = new double[numVars];
    
    for (int i = 0; i < numVars; ++i) {
      currentVars[i] = variables.getEntry(i);
    }
    
    double[] mag = evaluateResponse(currentVars);
    
    if (PRINT_EVERYTHING) {
      String in = Arrays.toString(currentVars);
      String out = Arrays.toString(mag);
      inputsPerCalculation.add(in);
      outputsPerCalculation.add(out);
      if (OUTPUT_TO_TERMINAL) {
        System.out.println("this is in "+in);
        System.out.println(out);
      }
    }
    
    double[][] jacobian = new double[mag.length][numVars];
    // now take the backward difference of each value 
    for (int i = 0; i < numVars; ++i) {
      
      if (i % 2 == 1 && currentVars[i] == 0.) {
        // imaginary value already zero, don't change this
        // we assume that if an imaginary value is NOT zero, it's close enough
        // to its correct value that it won't get turned down to zero
        for (int j = 0; j < mag.length; ++j) {
          jacobian[j][i] = 0.;
        }
        continue;
      }
      
      double[] changedVars = new double[currentVars.length];
      for (int j = 0; j < currentVars.length; ++j) {
        changedVars[j] = currentVars[j];
      }
      
      double diffX = changedVars[i] - DELTA;
      changedVars[i] = diffX;
      double[] diffY = 
          evaluateResponse(changedVars);
      
      for (int j = 0; j < diffY.length; ++j) {
        if (changedVars[i] - currentVars[i] == 0.) {
          jacobian[j][i] = 0.;
        } else {
          jacobian[j][i] = mag[j] - diffY[j];
          jacobian[j][i] /= currentVars[i] - changedVars[i];
        }
      }
      
    }
    
    RealVector result = MatrixUtils.createRealVector(mag);
    RealMatrix jMat = MatrixUtils.createRealMatrix(jacobian);
    if (OUTPUT_TO_TERMINAL) {
      // currently only looking at data about the sign of the jacobian
      int colDim = jMat.getColumnDimension();
      double[] rmsJbn = new double[colDim];
      for (int i = 0; i < colDim; ++i) {
        RealVector v = jMat.getColumnVector(i);
        double rms = 0.;
        int numPositive = 0;
        int numNegative = 0;
        for (int j = 0; j < v.getDimension(); ++j)  {
          double entry = v.getEntry(j);
          rms += Math.pow(entry, 2);
          if (entry < 0.) {
            ++numPositive;
          } else if (entry > 0.) {
            ++numNegative;
          }
        }
        rms /= v.getDimension();
        rms = Math.sqrt(rms);
        //String init = "Jacobian value for variable " + i;
        //if (numPositive > numNegative) {
         // System.out.println(init + " is mostly positive.");
          //System.out.println("Values: " + numPositive + ", " + numNegative);
        //} else if (numPositive < numNegative) {
         // System.out.println(init + " is mostly negative.");
          //System.out.println("Values: " + numPositive + ", " + numNegative);
        //} else {
         // System.out.println(init + " has equal +/-.");
          //System.out.println("Values: " + numPositive + ", " + numNegative);
        //}
        System.out.println("The RMS value is " + rms);
      }
      
      // get the residual values and print that out
      double resid = 0.;
      for (int i = 0; i < mag.length; ++i) {
        double sumSqd = Math.pow(mag[i] - observedResult[i], 2);
        resid += weights[i] * sumSqd;
      }
      System.out.println("Current residual: " + resid);
    }
    
    return new Pair<RealVector, RealMatrix>(result, jMat);
    
  }
  
  @Override
  public int[] listActiveResponseIndices() {
    // NOTE: not used by corresponding panel, overrides with active indices
    // of components in the combo-box
    return new int[]{1};
  }
  
  /**
   * Determines which poles to fit when doing the response curve fitting;
   * low frequency calibrations set the first two poles; high frequency
   * calibrations set the remaining poles
   * @param lowFreq True if a low frequency calibration is to be used
   */
  public void setLowFreq(boolean lowFreq) {
    this.lowFreq = lowFreq;
  }
  
  /**
   * Set whether or not to plot in units of frequency (Hz) or period (s)
   * @param setFreq true if plots should be in frequency units (Hz)
   */
  public void useFreqUnits(boolean setFreq) {
    freqSpace = setFreq;
  }
 
  /**
   * Simple validator method to enforce poles to be negative for their values
   * (Since imaginary values are stored in resps as their value and complex
   * conjugate, we can mandate this for all values in the vector, though only
   * real components are strictly required to be negative).
   * @param poleParams RealVector of parameters to be evaluated by solver
   * @return Vector of parameters but with components all negative
   */
  public RealVector validate(RealVector poleParams) {
    for (int i = 0; i < poleParams.getDimension(); ++i) {
      double value = poleParams.getEntry(i);
      if (value > 0 && (i % 2) == 0) {
        // even index means this is a real-value vector entry
        // if it's above zero, put it back below zero
        poleParams.setEntry(i, -DELTA - Double.MIN_VALUE);
      } else if (value > 0) {
        // this means the value is complex, we can multiply it by -1
        // this is ok for complex values since their conjugate is implied
        // to be part of the set of poles being fit
        poleParams.setEntry(i, value * -1);
      }
    }
    return poleParams;
  }

}
