package scala.scalajs.tools.io

import java.io._

import scala.scalajs.ir
import scala.scalajs.tools.sourcemap._

/** A virtual input file.
 */
trait VirtualFile {
  /** Path of the file, including everything.
   *  Unique if possible (used for lookup). */
  def path: String

  /** Name of the file, including extension */
  def name: String = {
    val pos = path.lastIndexOf('/')
    if (pos == -1) path
    else path.substring(pos + 1)
  }

  /** Optionally returns an implementation-dependent "version" token.
   *  Versions are compared with ==.
   *  If non-empty, a different version must be returned when the content
   *  changes. It should be equal if the content has not changed, but it is
   *  not mandatory.
   *  Such a token can be used by caches: the file need not be read and
   *  processed again if its version has not changed.
   */
  def version: Option[Any] = None
}

/** A virtual input file.
 */
trait VirtualTextFile extends VirtualFile {
  /** Returns the content of the file. */
  def content: String

  /** Returns a new Reader of the file. */
  def reader: Reader = new StringReader(content)

  /** Returns the lines in the content.
   *  Lines do not contain the new line characters.
   */
  def readLines(): List[String] = IO.readLines(reader)
}

object VirtualTextFile {
  def empty(path: String): VirtualTextFile =
    new MemVirtualTextFile(path)
}

/** A virtual binary input file.
 */
trait VirtualBinaryFile extends VirtualFile {
  /** Returns the content of the file. */
  def content: Array[Byte]

  /** Returns a new InputStream of the file. */
  def inputStream: InputStream = new ByteArrayInputStream(content)
}

/** A virtual input file which contains JavaScript code.
 *  It may have a source map associated with it.
 */
trait VirtualJSFile extends VirtualTextFile {
  /** Optionally, content of the source map file associated with this
   *  JavaScript source.
   */
  def sourceMap: Option[String] = None
}

object VirtualJSFile {
  def empty(path: String): VirtualJSFile =
    new MemVirtualJSFile(path).withVersion(Some(path))
}

/** A virtual JavaScript input file which was emitted by Scala.js as a
 *  "classfile".
 *  It has an info file associated with it.
 */
trait VirtualScalaJSClassfile extends VirtualJSFile {
  /** Content of the info file associated with this classfile. */
  def info: String
}

object VirtualScalaJSClassfile {
  def empty(path: String): VirtualScalaJSClassfile =
    new MemVirtualScalaJSClassfile(path).withVersion(Some(path))
}

/** A virtual JavaScript input file which was packed by Scala.js
 *  It has a pack info file associated with it.
 */
trait VirtualScalaJSPackfile extends VirtualJSFile {
  /** content of the pack info file associated with this packfile. */
  def packInfo: String
}

object VirtualScalaJSPackfile {
  def empty(path: String): VirtualScalaJSPackfile =
    new MemVirtualScalaJSPackfile(path).withVersion(Some(path))
}

/** A virtual Scala.js IR file.
 *  It contains the IR tree.
 *  It can also offer a [[VirtualJSFile]] view of its desugared content.
 */
trait VirtualScalaJSIRFile extends VirtualFile {
  /** Info tree of this file. */
  def info: ir.Trees.Tree =
    infoAndTree._1

  /** IR Tree of this file. */
  def tree: ir.Trees.ClassDef =
    infoAndTree._2

  /** Info tree and IR tree of this file. */
  def infoAndTree: (ir.Trees.Tree, ir.Trees.ClassDef)

  /** Converts this Scala.js IR file to a desugared Scala.js class file. */
  def toScalaJSClassfile: VirtualScalaJSClassfile = {
    val (info, tree) = this.infoAndTree

    val writer = new MemVirtualJSFileWriter
    val builder = new JSFileBuilderWithSourceMap(
        name,
        writer.contentWriter,
        writer.sourceMapWriter,
        relativizeSourceMapBasePath = None)
    builder.addIRTree(tree)
    builder.complete()
    val jsFile = writer.toVirtualFile(path)

    val p = path.stripSuffix(".sjsir") + ".js"
    new MemVirtualScalaJSClassfile(p)
      .withContent(jsFile.content)
      .withSourceMap(jsFile.sourceMap)
      .withInfo(info.toString())
      .withVersion(version)
  }
}

/** Base trait for virtual Scala.js IR files that are serialized as binary file.
 */
trait VirtualSerializedScalaJSIRFile extends VirtualBinaryFile with VirtualScalaJSIRFile {
  /** Info of this file. */
  override def info: ir.Trees.Tree = {
    // Overridden to avoid reading and deserializing the tree
    val stream = inputStream
    try ir.Serializers.deserialize(stream)
    finally stream.close()
  }

  /** IR Tree of this file. */
  override def infoAndTree: (ir.Trees.Tree, ir.Trees.ClassDef) = {
    val stream = inputStream
    try {
      val info = ir.Serializers.deserialize(stream)
      val tree = ir.Serializers.deserialize(stream).asInstanceOf[ir.Trees.ClassDef]
      (info, tree)
    } finally {
      stream.close()
    }
  }
}
