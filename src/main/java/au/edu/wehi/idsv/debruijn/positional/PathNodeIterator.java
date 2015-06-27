package au.edu.wehi.idsv.debruijn.positional;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;

import au.edu.wehi.idsv.debruijn.KmerEncodingHelper;
import au.edu.wehi.idsv.util.IntervalUtil;

import com.google.common.base.Function;
import com.google.common.collect.Iterators;
import com.google.common.collect.Ordering;
import com.google.common.collect.PeekingIterator;

/**
 * Transforms a start position sorted sequence of non-overlapping KmerAggregateNode to a
 * start position sorted sequence of KmerPathNode with graph edges 
 * @author Daniel Cameron
 *
 */
public class PathNodeIterator implements Iterator<KmerPathNode> {
	private final PeekingIterator<? extends KmerNode> underlying;
	private final int maxSupportWidth;
	private final int maxPathLength;
	private final int k;
	private final Long2ObjectOpenHashMap<List<GraphNode>> kmerNodes = new Long2ObjectOpenHashMap<List<GraphNode>>();
	private final PriorityQueue<GraphNode> active = new PriorityQueue<GraphNode>(1024, ByGraphNodeStartPosition);
	private final PriorityQueue<GraphNode> pathNodeConstructed = new PriorityQueue<GraphNode>(1024, ByGraphNodeFirstKmerStartPosition);
	private int lastProcessPosition = Integer.MIN_VALUE;
	public PathNodeIterator(Iterator<? extends KmerNode> it, int maxSupportWidth, int maxPathLength, int k) {
		this.underlying = Iterators.peekingIterator(it);
		this.maxSupportWidth = maxSupportWidth;
		this.maxPathLength = maxPathLength;
		this.k = k;
	}
	private static class GraphNode {
		public GraphNode(KmerNode node) {
			this.n = node;
		}
		public final KmerNode n;
		public KmerPathNode pn = null;
		/**
		 * Node we want to merge with
		 */
		public GraphNode mergeTarget = null;
		public boolean processed = false;
		public GraphNode prev = null;
	}
	private static final Ordering<GraphNode> ByGraphNodeStartPosition = KmerNode.ByStartPosition.onResultOf(
	        new Function<GraphNode, KmerNode>() {
	            public KmerNode apply(GraphNode gn) {
	                return gn.n;
	            }
	        });
	private static final Ordering<GraphNode> ByGraphNodeFirstKmerStartPosition = KmerPathNode.ByFirstKmerStartPosition.onResultOf(
        new Function<GraphNode, KmerPathNode>() {
            public KmerPathNode apply(GraphNode gn) {
                return gn.pn;
            }
        });
	@Override
	public boolean hasNext() {
		ensureBuffer();
		boolean complete = pathNodeConstructed.isEmpty();
		if (complete) {
			assert(active.isEmpty());
			assert(kmerNodes.isEmpty());
		}
		return !complete;
	}
	@Override
	public KmerPathNode next() {
		ensureBuffer();
		GraphNode gn = pathNodeConstructed.poll();
		removeFromGraph(gn);
		assert(gn.pn.length() <= maxPathLength);
		return gn.pn;
	}
	private void removeFromGraph(GraphNode node) {
		for (; node != null; node = node.prev) {
			List<GraphNode> existing = kmerNodes.get(node.n.kmer());
			assert(existing != null);
			existing.remove(node);
			if (existing.isEmpty()) {
				kmerNodes.remove(node.n.kmer());
			}
		}
	}
	private List<GraphNode> nextKmers(long kmer, int start, int end) {
		List<GraphNode> adj = new ArrayList<GraphNode>(4);
		for (long kmers : KmerEncodingHelper.nextStates(k, kmer)) {
			List<GraphNode> list = kmerNodes.get(kmers);
			if (list != null) {
				for (GraphNode n : list) {
					if (IntervalUtil.overlapsClosed(start + 1, end + 1, n.n.startPosition(), n.n.endPosition())) {
						adj.add(n);
					}
				}
			}
		}
		return adj;
	}
	private List<GraphNode> prevKmers(long kmer, int start, int end) {
		List<GraphNode> adj = new ArrayList<GraphNode>(4);
		for (long kmers : KmerEncodingHelper.prevStates(k, kmer)) {
			List<GraphNode> list = kmerNodes.get(kmers);
			if (list != null) {
				for (GraphNode n : list) {
					if (IntervalUtil.overlapsClosed(start - 1, end - 1, n.n.startPosition(), n.n.endPosition())) {
						adj.add(n);
					}
				}
			}
		}
		return adj;
	}
	private void ensureBuffer() {
		while (underlying.hasNext() && (pathNodeConstructed.isEmpty() || !edgesFullyDefined(pathNodeConstructed.peek()))) {
			KmerNode node = underlying.next();
			assert(node.startPosition() >= lastProcessPosition); // input should be sorted by start position
			addAggregateNodeToGraph(node);
			process(node.startPosition());
		}
		if (!underlying.hasNext()) {
			process(Integer.MAX_VALUE);
		}
	}
	/**
	 * Process 
	 * @param startPosition
	 */
	private void process(int earliestStartPositionOfUnprocessedKmer) {
		int completeKmerNodesExistBefore = earliestStartPositionOfUnprocessedKmer - maxSupportWidth - 2;
		// all active PathNodes ending before completeKmerNodesExistBefore can have successor values added
		while (!active.isEmpty() && active.peek().n.endPosition() < completeKmerNodesExistBefore) {
			process(active.poll());
		}
		lastProcessPosition = completeKmerNodesExistBefore;
	}
	private boolean edgesFullyDefined(GraphNode node) {
		// edges are fully defined if KmerPathNodes exist for all adjacent GraphNodes
		return node.pn.endPosition() < lastProcessPosition - maxSupportWidth - 2;
	}
	private void process(GraphNode gn) {
		assert(!gn.processed);
		gn.processed = true;
		// since active is sorted by start position, we are guaranteed that we have
		// processed all kmers intervals that start before our current start position
		// unfortunately, this is not particularly helpful as unprocessed prev nodes
		// to this GraphNode could still exist.
		// For example: AAA [3,5] starts after AAT [1,10] but is a prev node to the subset AAT [4,6]
		List<GraphNode> prevList = prevKmers(gn.n.kmer(), gn.n.startPosition(), gn.n.endPosition());
		if (prevList.size() == 1) {
			GraphNode prevNode = prevList.get(0);
			if (prevNode.mergeTarget == gn) {
				assert(prevNode.pn != null); // sanity check failure: not sorted by start position
				assert(prevNode.n.startPosition() == gn.n.startPosition() - 1);
				assert(prevNode.n.endPosition() == gn.n.endPosition() - 1);
				assert(prevNode.n.isReference() == gn.n.isReference());
				// merge with the previous node
				prevNode.pn.append(gn.n);
				gn.pn = prevNode.pn;
				gn.prev = prevNode;
			}
		} else {
			for (GraphNode n : prevList) {
				assert(n.mergeTarget == null || n.mergeTarget == gn);
				if (n.mergeTarget != null) {
					// we wanted to merge with this node
					// but our current node has multiple incoming paths
					// so we're not able to merge
					pathNodeConstructed.add(n);
				}
			}
		}
		List<GraphNode> nextList = nextKmers(gn.n.kmer(), gn.n.startPosition(), gn.n.endPosition());
		if (gn.pn == null) {
			gn.pn = new KmerPathNode(gn.n);
			// add PathNode edges that exist
			for (GraphNode n : nextList) {
				if (n.pn != null) {
					KmerPathNode.addEdge(gn.pn, n.pn);
				}
			}
			for (GraphNode n : prevList) {
				if (n.pn != null) {
					if (n.pn != gn.pn) {
						KmerPathNode.addEdge(n.pn, gn.pn);
					} else {
						// self-intersection special case: should have already been added
						// since adding self to next list also adds to prev list
						assert(n.pn.next().contains(gn.pn));
					}
				}
			}
		}
		if (nextList.size() == 1 && gn.pn.length() < maxPathLength) {
			GraphNode nextNode = nextList.get(0);
				if (nextNode.n.isReference() == gn.n.isReference()
						&& nextNode.n.startPosition() == gn.n.startPosition() + 1
						&& nextNode.n.endPosition() == gn.n.endPosition() + 1) {
				// We want to merge with our next node.
				// Delay processing until we know what's happening with the next node.
				// We can't perform the merge now as the adjacent nodes
				// for the next node may not yet be in the graph so we don't know if
				// there are any alternate paths to the next node yet.
				gn.mergeTarget = nextList.get(0);
				assert(nextNode.pn == null);
			}
		}
		if (gn.mergeTarget == null) {
			// don't want to merge with a successor = path complete
			pathNodeConstructed.add(gn);
		}
	}
	private void addAggregateNodeToGraph(KmerNode node) {
		if (node.width() > maxSupportWidth) {
			throw new RuntimeException("Sanity check failure: support width greater than maximum");
		}
		GraphNode gn = new GraphNode(node);
		active.add(gn);
		List<GraphNode> existing = kmerNodes.get(node.kmer());
		if (existing == null) {
			existing = new LinkedList<GraphNode>();
			kmerNodes.put(node.kmer(), existing);
		}
		existing.add(gn);
	}
	@Override
	public void remove() {
		throw new UnsupportedOperationException();
	}
}
