package asl.sensor.test;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.math3.complex.Complex;
import org.junit.Test;

import asl.sensor.utils.NumericUtils;

public class NumericUtilsTest {

  @Test
  public void realSorterWorksRight() {
    List<Complex> cs = new ArrayList<Complex>();
    cs.add( new Complex(1, 0) );
    cs.add( new Complex(2, 0) );
    cs.add( new Complex(-1, 0) );
    cs.add( new Complex(-1, -1) );
    cs.add( new Complex(1, -1) );
    cs.add( new Complex(-3, -3) );
    cs.add( new Complex(-3, 3) );
    NumericUtils.complexRealsFirstSorter(cs);
    Complex[] csl = new Complex[ cs.size() ];
    csl[0] = new Complex(-1, 0);
    csl[1] = new Complex(1, 0);
    csl[2] = new Complex(2, 0);
    csl[3] = new Complex(-3, -3);
    csl[4] = new Complex(-3, 3);
    csl[5] = new Complex(-1, -1);
    csl[6] = new Complex(1, -1);
    for (int i = 0; i < csl.length; ++i) {
      assertTrue( cs.get(i).equals(csl[i]) );
    }
  }
  
  @Test
  public void movingAverageCorrectValues() {
    double[] averaged = new double[]{1./3.,  1.0, 2.0, 3.0, 4.0};
    double[] init = new double[]{1., 2., 3., 4., 5.};
    double[] test = NumericUtils.multipointMovingAverage(init, 3);
    for (int i = 0; i < test.length; ++i) {
      assertEquals(averaged[i], test[i], 1E-25);
    }
  }

}
  
