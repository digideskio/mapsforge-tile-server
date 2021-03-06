package org.develar.mapsforgeTileServer

import org.mapdb.DataInput2
import org.mapdb.DataOutput2
import org.mapdb.Serializer
import java.io.DataInput
import java.io.DataOutput
import java.io.Serializable

class RenderedTileSerializer() : Serializer<RenderedTile>, Serializable {
  override fun serialize(out: DataOutput, value: RenderedTile) {
    DataOutput2.packLong(out, value.lastModified)
    Serializer.STRING_ASCII.serialize(out, value.etag)
    Serializer.BYTE_ARRAY.serialize(out, value.data)
  }

  override fun deserialize(`in`: DataInput, available: Int): RenderedTile {
    val lastModified = DataInput2.unpackLong(`in`)
    val etag = Serializer.STRING_ASCII.deserialize(`in`, available)
    val data = Serializer.BYTE_ARRAY.deserialize(`in`, available)!!
    return RenderedTile(data, lastModified, etag)
  }

  override fun fixedSize() = -1
}