package org.heigit.bigspatialdata.hosmdb.osh;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.heigit.bigspatialdata.hosmdb.osh.builder.Builder;
import org.heigit.bigspatialdata.hosmdb.osm.OSMEntity;
import org.heigit.bigspatialdata.hosmdb.osm.OSMMember;
import org.heigit.bigspatialdata.hosmdb.osm.OSMNode;
import org.heigit.bigspatialdata.hosmdb.osm.OSMWay;
import org.heigit.bigspatialdata.hosmdb.util.BoundingBox;
import org.heigit.bigspatialdata.hosmdb.util.ByteArrayOutputWrapper;
import org.heigit.bigspatialdata.hosmdb.util.ByteArrayWrapper;

public class HOSMWay extends HOSMEntity
    implements Iterable<OSMWay>, Serializable {

  private static final long serialVersionUID = 1L;

  private static final int CHANGED_USER_ID = 1 << 0;
  private static final int CHANGED_TAGS = 1 << 1;
  private static final int CHANGED_REFS = 1 << 2;

  private static final int HEADER_MULTIVERSION = 1 << 0;
  private static final int HEADER_TIMESTAMPS_NOT_IN_ORDER = 1 << 1;
  private static final int HEADER_HAS_TAGS = 1 << 2;
  private static final byte HEADER_HAS_NO_NODES = 1 << 3;



  private final IntBuffer nodeIndex;
  private final int nodeDataOffset;
  private final int nodeDataLength;



  public static HOSMWay instance(final byte[] data, final int offset, final int length)
      throws IOException {
    return instance(data, offset, length, 0, 0, 0, 0);
  }

  public static HOSMWay instance(final byte[] data, final int offset, final int length,
      final long baseId, final long baseTimestamp, final long baseLongitude,
      final long baseLatitude) throws IOException {


    ByteArrayWrapper wrapper = ByteArrayWrapper.newInstance(data, offset, length);
    final byte header = wrapper.readRawByte();


    final long minLon = baseLongitude + wrapper.readSInt64();
    final long maxLon = minLon + wrapper.readUInt64();
    final long minLat = baseLatitude + wrapper.readSInt64();
    final long maxLat = minLat + wrapper.readUInt64();

    final BoundingBox bbox =
        new BoundingBox(minLon * OSMNode.GEOM_PRECISION, maxLon * OSMNode.GEOM_PRECISION,
            minLat * OSMNode.GEOM_PRECISION, maxLat * OSMNode.GEOM_PRECISION);

    final int[] keys;
    if ((header & HEADER_HAS_TAGS) != 0) {
      final int size = wrapper.readUInt32();
      keys = new int[size];
      for (int i = 0; i < size; i++) {
        keys[i] = wrapper.readUInt32();
      }
    } else {
      keys = new int[0];
    }

    final long id = wrapper.readUInt64() + baseId;

    final IntBuffer nodeIndex;
    final int nodeDataLength;
    final int nodeDataOffset;
    if ((header & HEADER_HAS_NO_NODES) == 0) {
      final int nodeIndexLength = wrapper.readUInt32();
      final byte[] nodeIndexBuffer = wrapper.readByteArray(nodeIndexLength);

      nodeIndex = ByteBuffer.wrap(nodeIndexBuffer).asIntBuffer();

      nodeDataLength = wrapper.readUInt32();
      nodeDataOffset = wrapper.getPos();
    } else {
      nodeIndex = ByteBuffer.wrap(new byte[0]).asIntBuffer();
      nodeDataLength = 0;
      nodeDataOffset = 0;
    }

    final int dataOffset = nodeDataOffset + nodeDataLength;
    final int dataLength = length - (dataOffset - offset);

    return new HOSMWay(data, offset, length, baseId, baseTimestamp, baseLongitude, baseLatitude,
        header, id, bbox, keys, dataOffset, dataLength, nodeIndex, nodeDataOffset, nodeDataLength);
  }

  private HOSMWay(final byte[] data, final int offset, final int length, final long baseId,
      final long baseTimestamp, final long baseLongitude, final long baseLatitude,
      final byte header, final long id, final BoundingBox bbox, final int[] keys,
      final int dataOffset, final int dataLength, final IntBuffer nodeIndex,
      final int nodeDataOffset, final int nodeDataLength) {
    super(data, offset, length, baseId, baseTimestamp, baseLongitude, baseLatitude, header, id,
        bbox, keys, dataOffset, dataLength);

    this.nodeIndex = nodeIndex;
    this.nodeDataOffset = nodeDataOffset;
    this.nodeDataLength = nodeDataLength;
  }

  public List<OSMWay> getVersions() {
    List<OSMWay> versions = new ArrayList<>();
    this.forEach(versions::add);
    return versions;
  }


  @Override
  public Iterator<OSMWay> iterator() {
    try {
    final List<HOSMNode> nodes = getNodes();
    return new Iterator<OSMWay>() {
      ByteArrayWrapper wrapper = ByteArrayWrapper.newInstance(data, dataOffset, dataLength);

      int version = 0;
      long timestamp = 0;
      long changeset = 0;
      int userId = 0;
      int[] keyValues = new int[0];
     
      OSMMember[] members = new OSMMember[0];

      @Override
      public boolean hasNext() {
        return wrapper.hasLeft() > 0;
      }

      @Override
      public OSMWay next() {
        try {
          version = wrapper.readSInt32() + version;
          timestamp = wrapper.readSInt64() + timestamp;
          changeset = wrapper.readSInt64() + changeset;

          byte changed = wrapper.readRawByte();

          if ((changed & CHANGED_USER_ID) != 0) {
            userId = wrapper.readSInt32() + userId;
          }

          if ((changed & CHANGED_TAGS) != 0) {
            int size = wrapper.readUInt32();
            keyValues = new int[size];
            for (int i = 0; i < size; i++) {
              keyValues[i] = wrapper.readUInt32();
            }
          }

          if ((changed & CHANGED_REFS) != 0) {
            int size = wrapper.readUInt32();
            members = new OSMMember[size];
            long memberId = 0;
            int memberOffset = 0;
            HOSMEntity member = null;
            for (int i = 0; i < size; i++) {
               memberOffset = wrapper.readUInt32();
               if(memberOffset > 0){
                 member = nodes.get(memberOffset-1);
                 memberId =  member.getId();
                
               }else{
                 member = null;
                 memberId = wrapper.readSInt64() + memberId;
               }
               members[i] = new OSMMember(memberId, NODE, -1,member);
            }
          }

          return new OSMWay(id, version, baseTimestamp + timestamp, changeset, userId, keyValues,members);
        } catch (IOException e) {
          e.printStackTrace();
        }
        return null;
      }
    };
    } catch (IOException e1) {
    }
    return Collections.emptyIterator();
  }

  public List<HOSMNode> getNodes() throws IOException {
    List<HOSMNode> nodes = new ArrayList<>(nodeIndex.limit());
    for (int index = 0; index < nodeIndex.limit(); index++) {
      int offset = nodeIndex.get(index);
      int length =
          ((index < nodeIndex.limit() - 1) ? nodeIndex.get(index + 1) : nodeDataLength) - offset;
      nodes.add(HOSMNode.instance(data, nodeDataOffset + offset, length, 0, 0, baseLongitude,
          baseLatitude));
    }
    return nodes;
  }

  @Override
  public HOSMWay rebase(long baseId, long baseTimestamp, long baseLongitude, long baseLatitude)
      throws IOException {
    List<OSMWay> versions = getVersions();
    List<HOSMNode> nodes = getNodes();
    return build(versions, nodes, baseId, baseTimestamp, baseLongitude, baseLatitude);
  }

  public static HOSMWay build(List<OSMWay> versions, Collection<HOSMNode> nodes)
      throws IOException {
    return build(versions, nodes, 0, 0, 0, 0);
  }

  public static HOSMWay build(List<OSMWay> versions, Collection<HOSMNode> nodes, final long baseId,
      final long baseTimestamp, final long baseLongitude, final long baseLatitude)
      throws IOException {
    Collections.sort(versions, Collections.reverseOrder());
    ByteArrayOutputWrapper output = new ByteArrayOutputWrapper();


    OSMMember[] lastRefs = new OSMMember[0];


    long id = versions.get(0).getId();

    long minLon = Long.MAX_VALUE;
    long maxLon = Long.MIN_VALUE;
    long minLat = Long.MAX_VALUE;
    long maxLat = Long.MIN_VALUE;

    Map<Long, Integer> nodeOffsets = new HashMap<>();
    ByteBuffer bbIndex = ByteBuffer.allocate(nodes.size() * 4);
    IntBuffer ibIndex = bbIndex.asIntBuffer();

    ByteArrayOutputWrapper nodeData = new ByteArrayOutputWrapper();
    int offset = 0;
    int idx = 0;
    for (HOSMNode node : nodes) {
      node = node.rebase(0, 0, baseLongitude, baseLatitude);
      nodeOffsets.put(node.getId(), idx);
      ibIndex.put(idx++, offset);
      offset += node.getLength();
      node.writeTo(nodeData);
      Iterator<OSMNode> osmItr = node.iterator();
      while (osmItr.hasNext()) {
        OSMNode osm = osmItr.next();
        if (osm.isVisible()) {
          minLon = Math.min(minLon, osm.getLon());
          maxLon = Math.max(maxLon, osm.getLon());

          minLat = Math.min(minLat, osm.getLat());
          maxLat = Math.max(maxLat, osm.getLat());
        }
      }
    }


    Builder builder = new Builder(output, baseId, baseTimestamp, baseLongitude, baseLatitude);

    for (int i = 0; i < versions.size(); i++) {
      OSMWay way = versions.get(i);
      OSMEntity version = way;

      byte changed = 0;
      OSMMember[] refs = way.getRefs();
      if (version.isVisible() && !memberEquals(refs, lastRefs)) {
        changed |= CHANGED_REFS;
      }

      builder.build(version, changed);
      if ((changed & CHANGED_REFS) != 0) {
        long lastMemberId = 0;
        output.writeUInt32(refs.length);
        for (OSMMember ref : refs) {
          Integer refOffset = nodeOffsets.get(Long.valueOf(ref.getId()));
          if (refOffset == null) {
            output.writeUInt32(0);
            output.writeSInt64(ref.getId() - lastMemberId);
          } else {
            output.writeUInt32(refOffset.intValue()+1);
          }
          lastMemberId = ref.getId();
        }
        lastRefs = refs;
      }
    }

    // store nodes


    ByteArrayOutputWrapper record = new ByteArrayOutputWrapper();

    byte header = 0;
    if (versions.size() > 1)
      header |= HEADER_MULTIVERSION;
    if (builder.getTimestampsNotInOrder())
      header |= HEADER_TIMESTAMPS_NOT_IN_ORDER;
    if (builder.getKeySet().size() > 0)
      header |= HEADER_HAS_TAGS;
    if (nodes.isEmpty())
      header |= HEADER_HAS_NO_NODES;

    record.writeByte(header);

    record.writeSInt64(minLon - baseLongitude);
    record.writeUInt64(maxLon - minLon);
    record.writeSInt64(minLat - baseLatitude);
    record.writeUInt64(maxLat - minLat);

    if ((header & HEADER_HAS_TAGS) != 0) {
      record.writeUInt32(builder.getKeySet().size());
      for (Integer key : builder.getKeySet()) {
        record.writeUInt32(key.intValue());
      }
    }

    record.writeUInt64(id - baseId);

    if ((header & HEADER_HAS_NO_NODES) == 0) {
      byte[] nodeIndexOutput = bbIndex.array();
      record.writeUInt32(nodeIndexOutput.length);
      record.writeByteArray(nodeIndexOutput);

      byte[] nodesOutput = nodeData.toByteArray();
      record.writeUInt32(nodesOutput.length);
      record.writeByteArray(nodesOutput);
    }

    byte[] waysOutput = output.toByteArray();
    record.writeByteArray(waysOutput);

    byte[] data = record.toByteArray();
    return HOSMWay.instance(data, 0, data.length);
  }

  
  private static boolean memberEquals(OSMMember[] a, OSMMember[] b){
    if(a.length != b.length)
      return false;
    for(int i=0; i<a.length; i++){
      if(a[i].getId() != b[i].getId())
        return false;
    }
    return true;
  }

  @Override
  public String toString() {
    return String.format("Way (%d)", id);
  }

  private Object writeReplace() {
    return new SerializationProxy(this);
  }

  private static class SerializationProxy implements Externalizable {

    private final HOSMEntity entity;
    private byte[] data;

    public SerializationProxy(HOSMEntity entity) {
      this.entity = entity;
    }

    public SerializationProxy() {
      this.entity = null;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
      out.writeInt(entity.getLength());
      entity.writeTo(out);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
      int length = in.readInt();
      data = new byte[length];
      in.readFully(data);
    }

    private Object readResolve() {
      try {
        return HOSMWay.instance(data, 0, data.length);
      } catch (IOException e) {
        e.printStackTrace();
      }
      return null;
    }
  }
}
