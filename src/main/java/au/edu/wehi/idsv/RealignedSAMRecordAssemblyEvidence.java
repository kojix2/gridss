package au.edu.wehi.idsv;

import htsjdk.samtools.SAMRecord;
import au.edu.wehi.idsv.sam.SAMRecordUtil;

public class RealignedSAMRecordAssemblyEvidence extends SAMRecordAssemblyEvidence implements DirectedBreakpoint {
	private final RealignedBreakpoint rbp;
	public RealignedSAMRecordAssemblyEvidence(
			ProcessingContext processContext,
			AssemblyEvidenceSource source,
			SAMRecordAssemblyEvidence assembly,
			SAMRecord realigned) {
		super(source, assembly, realigned);
		this.rbp = new RealignedBreakpoint(processContext, super.getBreakendSummary(), super.getAssemblyAnchorSequence(), realigned);
		SAMRecordUtil.pairReads(getSAMRecord(), getRemoteSAMRecord());
	}
	public RealignedSAMRecordAssemblyEvidence(
			ProcessingContext processContext,
			AssemblyEvidenceSource source,
			SAMRecord assembly,
			SAMRecord realigned) {
		super(source, assembly, realigned);
		this.rbp = new RealignedBreakpoint(processContext, super.getBreakendSummary(), super.getAssemblyAnchorSequence(), realigned);
		SAMRecordUtil.pairReads(getSAMRecord(), getRemoteSAMRecord());
	}
	@Override
	public BreakpointSummary getBreakendSummary() {
		return rbp.getBreakpointSummary();
	}
	@Override
	public int getRemoteMapq() {
		return getRemoteSAMRecord().getMappingQuality();
	}
	@Override
	public int getRemoteBaseLength() {
		return getRemoteSAMRecord().getReadLength();
	}
	@Override
	public int getRemoteBaseCount() {
		return getRemoteBaseLength();
	}
	@Override
	public int getRemoteMaxBaseQual() {
		return SAMRecordUtil.getMaxReferenceBaseQual(getRemoteSAMRecord());
	}
	@Override
	public int getRemoteTotalBaseQual() {
		return SAMRecordUtil.getTotalReferenceBaseQual(getRemoteSAMRecord());
	}
	@Override
	public String getUntemplatedSequence() {
		return rbp.getInsertedSequence();
	}
}
