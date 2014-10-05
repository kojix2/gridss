package au.edu.wehi.idsv.metrics;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMReadGroupRecord;
import htsjdk.samtools.SamPairUtil.PairOrientation;
import htsjdk.samtools.metrics.MetricsFile;
import htsjdk.samtools.util.CollectionUtil;
import htsjdk.samtools.util.Log;

import java.io.File;
import java.util.List;

import picard.analysis.CollectInsertSizeMetrics;
import picard.analysis.InsertSizeMetrics;
import picard.analysis.MetricAccumulationLevel;
import picard.analysis.directed.InsertSizeMetricsCollector;
import au.edu.wehi.idsv.ProcessingContext;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;

public class IdsvSamFileMetrics implements RelevantMetrics {
	private static final Log log = Log.getInstance(IdsvSamFileMetrics.class);
	private final ProcessingContext processContext;
	private InsertSizeMetrics insertSize = null;
	private IdsvMetrics idsvMetrics = null;
	private InsertSizeDistribution insertDistribution = null;
	/**
	 * Creates a metric collector to record metrics required by idsv
	 * @param header SAM header of file to process
	 * @return metric collector
	 */
	public static InsertSizeMetricsCollector createInsertSizeMetricsCollector(SAMFileHeader header) {
		List<SAMReadGroupRecord> rg = ImmutableList.<SAMReadGroupRecord>of();
		if (header != null) {
			rg = header.getReadGroups();
		}
		return new InsertSizeMetricsCollector(
    			CollectionUtil.makeSet(MetricAccumulationLevel.ALL_READS), null, //, MetricAccumulationLevel.SAMPLE), rg,
				// match CollectInsertSizeMetrics defaults
				new CollectInsertSizeMetrics().MINIMUM_PCT,
				new CollectInsertSizeMetrics().Histogram_WIDTH,
				new CollectInsertSizeMetrics().DEVIATIONS);
	}
	/**
	 * Creates a metric collector to record metrics required by idsv
	 * @param header SAM header of file to process
	 * @return metric collector
	 */
	public static IdsvMetricsCollector createIdsvMetricsCollector() {
		return new IdsvMetricsCollector();
	}
	public IdsvSamFileMetrics(ProcessingContext processContext, File insertSizeMetricsFile, File idsvMetricsFiles) {
		this(processContext, getInsertSizeMetrics(insertSizeMetricsFile), getIdsvMetrics(idsvMetricsFiles), getInsertSizeDistribution(insertSizeMetricsFile));
	}
	private static InsertSizeDistribution getInsertSizeDistribution(File insertSizeMetricsFile) {
		return InsertSizeDistribution.create(insertSizeMetricsFile);
	}
	private static IdsvMetrics getIdsvMetrics(File idsvMetricsFiles) {
		IdsvMetrics metric = Iterators.getOnlyElement(Iterables.filter(MetricsFile.readBeans(idsvMetricsFiles), IdsvMetrics.class).iterator(), null);
		if (metric == null) {
			metric = new IdsvMetrics();
			log.error(String.format("No idsv metrics found in %s.", idsvMetricsFiles));
		}
		return metric;
	}
	private static InsertSizeMetrics getInsertSizeMetrics(File insertSizeMetricsFile) {
		InsertSizeMetrics insertSize = null;
		for (InsertSizeMetrics metric : Iterables.filter(MetricsFile.readBeans(insertSizeMetricsFile), InsertSizeMetrics.class)) {
			if (metric.SAMPLE == null && metric.LIBRARY == null && metric.READ_GROUP == null) {
				insertSize = metric;
			}
		}
		if (insertSize == null) {
			insertSize = new InsertSizeMetrics();
			log.error(String.format("No pair-end insert size metrics found in %s.", insertSizeMetricsFile));
		}
		return insertSize;
	}
	public IdsvSamFileMetrics(ProcessingContext processContext, InsertSizeMetrics insertSize, IdsvMetrics idsvMetrics, InsertSizeDistribution insertDistribution) {
		this.processContext = processContext;
		this.insertSize = insertSize;
		this.idsvMetrics = idsvMetrics;
		this.insertDistribution = insertDistribution;
	}
	/* (non-Javadoc)
	 * @see au.edu.wehi.idsv.RelevantMetrics#getMedianFragmentSize()
	 */
	@Override
	public double getMedianFragmentSize() {
		// TODO: is this 5' difference or frag size?
		return insertSize.MEDIAN_INSERT_SIZE;
	}
	/* (non-Javadoc)
	 * @see au.edu.wehi.idsv.RelevantMetrics#getFragmentSizeStdDev()
	 */
	@Override
	public double getFragmentSizeStdDev() {
		return 1.4826 * insertSize.MEDIAN_ABSOLUTE_DEVIATION;
	}
	/* (non-Javadoc)
	 * @see au.edu.wehi.idsv.RelevantMetrics#getMaxFragmentSize()
	 */
	@Override
	public int getMaxFragmentSize() {
		int fragSize = getMaxReadLength();
		// TODO: make sure this still works for RF and TANDOM read pairs
		if (processContext.getReadPairParameters().useProperPairFlag) {
			fragSize = Math.max(fragSize, idsvMetrics.MAX_PROPER_PAIR_FRAGMENT_LENGTH);
		} else {
			fragSize = Math.max(fragSize, insertDistribution.inverseCumulativeProbability(processContext.getReadPairParameters().getCordantPercentageUpperBound()));
		}
		// return (int)Math.ceil(getMedianFragmentSize() + 3 * getFragmentSizeStdDev());
		return fragSize;
	}
	/* (non-Javadoc)
	 * @see au.edu.wehi.idsv.RelevantMetrics#getPairOrientation()
	 */
	@Override
	public PairOrientation getPairOrientation() {
		if (insertSize == null) return null;
		return insertSize.PAIR_ORIENTATION;
	}
	@Override
	public int getMaxReadLength() {
		return idsvMetrics.MAX_READ_LENGTH;
	}
	@Override
	public InsertSizeDistribution getInsertSizeDistribution() {
		return insertDistribution;
	}
}