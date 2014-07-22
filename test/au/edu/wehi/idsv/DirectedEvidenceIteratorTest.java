package au.edu.wehi.idsv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import htsjdk.samtools.SAMRecord;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;

public class DirectedEvidenceIteratorTest extends TestHelper {
	private List<SAMRecord> sv;
	private List<SAMRecord> mate;
	private List<SAMRecord> realigned;	
	private List<VariantContext> vcf;
	private List<DirectedEvidence> out;
	@Before
	public void setup() {
		sv = Lists.newArrayList();
		mate = Lists.newArrayList();
		realigned = Lists.newArrayList();
		vcf = Lists.newArrayList();
		out = Lists.newArrayList();
	}
	public void go() {
		sv = sorted(sv);
		mate = mateSorted(mate);
		DirectedEvidenceIterator it = new DirectedEvidenceIterator(
				getContext(),
				SES(),
				sv == null ? null : Iterators.peekingIterator(sv.iterator()),
				mate == null ? null : Iterators.peekingIterator(mate.iterator()),
				realigned == null ? null : Iterators.peekingIterator(realigned.iterator()),
				vcf == null ? null : Iterators.peekingIterator(vcf.iterator()));
		while (it.hasNext()) {
			out.add(it.next());
		}
		// check output is in order
		for (int i = 0; i < out.size() - 1; i++) {
			BreakendSummary l0 = out.get(i).getBreakendSummary();
			BreakendSummary l1 = out.get(i).getBreakendSummary();
			assertTrue(l0.referenceIndex < l1.referenceIndex || (l0.referenceIndex == l1.referenceIndex && l0.start <= l1.start));
		}
	}
	@Test
	public void should_pair_oea_with_mate() {
		sv.add(OEA(0, 1, "100M", true)[0]);
		mate.add(OEA(0, 1, "100M", true)[1]);
		go();
		assertEquals(1, out.size());
		assertTrue(out.get(0) instanceof NonReferenceReadPair);
	}
	@Test
	public void should_pair_dp_with_mate() {
		sv.add(DP(0, 1, "100M", true, 1, 1, "100M", true)[0]);
		mate.add(DP(0, 1, "100M", true, 1, 1, "100M", true)[1]);
		go();
		assertEquals(1, out.size());
		assertTrue(out.get(0) instanceof NonReferenceReadPair);
	}
	@Test
	public void should_match_sc_with_realign() {
		sv.add(withReadName("ReadName", Read(0, 1, "5S10M5S"))[0]);
		realigned.add(withReadName("0#1#bReadName", Read(0, 1, "5M"))[0]);
		realigned.add(withReadName("0#10#fReadName", Read(0, 1, "5M"))[0]);
		go();
		assertEquals(2, out.size());
		assertTrue(out.get(0) instanceof SoftClipEvidence);
		assertTrue(out.get(0).getBreakendSummary() instanceof BreakpointSummary);
	}
	@Test
	public void should_return_sc() {
		SAMRecord r = Read(0, 1, "5S10M5S");
		sv.add(r);
		go();
		// forward and backward
		assertEquals(2, out.size());
		assertTrue(out.get(0) instanceof SoftClipEvidence);
		assertTrue(out.get(1) instanceof SoftClipEvidence);
	}
	public VariantContextDirectedEvidence BE(int position) {
		return new AssemblyBuilder(getContext(), AES())
			.assemblerName("test")
			.assemblyBases(B("AA"))
			.anchorLength(1)
			.direction(BWD)
			.referenceAnchor(0, position)
			.assembledBaseCount(5, 6)
			.assemblyBaseQuality(new byte[] { 7,7 } )
			.makeVariant();
	}
	@Test
	public void should_return_assembly() {
		VariantContextDirectedEvidence assembly = BE(1);
		vcf.add(new VariantContextBuilder(assembly).make());
		go();
		assertEquals(1, out.size());
		assertTrue(out.get(0) instanceof VariantContextDirectedEvidence);
	}
	@Test
	public void should_match_assembly_with_realign() {
		VariantContextDirectedEvidence assembly = BE(1);
		vcf.add(new VariantContextBuilder(assembly).make());
		SAMRecord r = Read(1, 10, "1M");
		r.setReadName("0#1#" + vcf.get(0).getID());
		realigned.add(r);
		go();
		assertEquals(1, out.size());
		assertTrue(out.get(0) instanceof VariantContextDirectedEvidence);
		assertTrue(out.get(0).getBreakendSummary() instanceof BreakpointSummary);
	}
	@Test
	public void should_flag_assembly_if_realign_unmapped() {
		VariantContextDirectedEvidence assembly = BE(1);
		vcf.add(new VariantContextBuilder(assembly).make());
		SAMRecord r = Unmapped(1);
		r.setReadName("0#1#" + vcf.get(0).getID());
		realigned.add(r);
		go();
		assertEquals(1, out.size());
		assertTrue(out.get(0) instanceof VariantContextDirectedEvidence);
		assertTrue(out.get(0).getBreakendSummary() instanceof BreakendSummary);
	}
	@Test
	public void should_ignore_non_sv_reads() {
		sv.add(RP(0, 1, 2, 1)[0]);
		sv.add(RP(0, 1, 2, 1)[1]);
		go();
		assertEquals(0, out.size());
	}
	@Test
	public void should_expect_mates_in_order() {
		sv.add(withReadName("DP", DP(0, 2, "100M", true, 1, 1, "100M", true))[0]);
		mate.add(withReadName("DP", DP(0, 2, "100M", true, 1, 1, "100M", true))[1]);
		sv.add(withReadName("OEA", OEA(0, 1, "100M", true))[0]);
		mate.add(withReadName("OEA", OEA(0, 1, "100M", true))[1]);
		go();
		assertEquals(2, out.size());
		assertTrue(out.get(0) instanceof NonReferenceReadPair);
		assertTrue(out.get(1) instanceof NonReferenceReadPair);
	}
	@Test
	public void should_allow_realign_in_order_at_same_position() {
		SAMRecord r = withReadName("ReadName", Read(0, 1, "5S10M5S"))[0];
		sv.add(r);
		SAMRecord f = withReadName("0#10#fReadName", Read(0, 1, "5M"))[0];
		SAMRecord b = withReadName("0#1#bReadName", Read(0, 1, "5M"))[0];
		VariantContextDirectedEvidence assembly = BE(1);
		vcf.add(new VariantContextBuilder(assembly).make());
		SAMRecord assemblyRealigned = withReadName("0#1#" + vcf.get(0).getID(), Read(1, 10, "1M"))[0];
		realigned.add(b);
		realigned.add(assemblyRealigned);
		realigned.add(f);
		go();
		assertEquals(3, out.size());
		assertTrue(out.get(0).getBreakendSummary() instanceof BreakpointSummary);
		assertTrue(out.get(1).getBreakendSummary() instanceof BreakpointSummary);
		assertTrue(out.get(2).getBreakendSummary() instanceof BreakpointSummary);
	}
	@Test
	public void should_require_realign_in_call_position_order() {
		SAMRecord r = withReadName("ReadName", Read(0, 1, "5S10M5S"))[0];
		sv.add(r);
		VariantContextDirectedEvidence assembly = BE(2);
		vcf.add(new VariantContextBuilder(assembly).make());
		realigned.add(withReadName("0#1#bReadName", Read(0, 1, "5M"))[0]);
		realigned.add(withReadName("0#2#" + vcf.get(0).getID(), Read(1, 10, "1M"))[0]);
		realigned.add(withReadName("0#10#fReadName", Read(0, 1, "5M"))[0]);
		go();
		assertEquals(3, out.size());
		assertTrue(out.get(0) instanceof SoftClipEvidence); // backward
		assertTrue(out.get(1) instanceof VariantContextDirectedEvidence);
		assertTrue(out.get(2) instanceof SoftClipEvidence); // forward
		assertTrue(out.get(0).getBreakendSummary() instanceof BreakpointSummary);
		assertTrue(out.get(1).getBreakendSummary() instanceof BreakpointSummary);
		assertTrue(out.get(2).getBreakendSummary() instanceof BreakpointSummary);
	}
	@Test
	public void should_ignore_filtered_variants() {
		VariantContextDirectedEvidence assembly = AB()
				.contributingEvidence(Lists.newArrayList((DirectedEvidence)SCE(FWD, Read(0, 1, "1M2S"))))
				.makeVariant();
		vcf.add(assembly);
		go();
		assertEquals(0, out.size());
	}
	@Test
	public void should_ignore_filtered_softclips() {
		sv.add(withMapq(0, withReadName("ReadName", Read(0, 1, "5S10M5S")))[0]);
		go();
		assertEquals(0, out.size());
	}
}