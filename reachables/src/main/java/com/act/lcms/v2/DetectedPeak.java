package com.act.lcms.v2;

/**
 * An interface for representing detected LCMS peaks
 */
public interface DetectedPeak {
  /**
   * Return the estimated peak m/z value
   */
  Double getMz();

  /**
   * Return the estimated peak retention time (in sec)
   */
  Double getRetentionTime();

  /**
   * Return the estimated peak intensity
   */
  Double getIntensity();

  /**
   * Return the id of the source scan file
   */
  String getSourceScanFileId();

  /**
   * Return the peak confidence (likelihood that the detected peak is actually a peak)
   */
  Double getConfidence();

  /**
   * Test whether an input m/z value matches with a detected peak at a certain confidence level.
   * @param mz input m/z value
   * @param confidenceLevel input confidence level
   * @return result of the test, true if the m/z value matches with the peak
   */
  Boolean matchesMz(Double mz, Double confidenceLevel);

  /**
   * Test whether an input m/z value and retention time matches with a detected peak at a certain confidence level.
   * @param mz input m/z value
   * @param retentionTime input retention time
   * @param confidenceLevel input confidence level
   * @return result of the test, true if the m/z value matches with the peak
   */
  Boolean matchesMzTime(Double mz, Double retentionTime, Double confidenceLevel);
}
