package replication

class PeerPair(val left: String, val right: String) {

  def consistsString(s: String): Boolean = {
    this.left == s || this.right == s
  }

  def canEqual(a: Any) = a.isInstanceOf[PeerPair]

  override def equals(that: Any): Boolean =
    that match {
      case that: PeerPair => {
        that.canEqual(this) &&
          (
            (this.left == that.left &&
              this.right == that.right)
              ||
              (this.left == that.right &&
                this.right == that.left)
            )
      }
      case _ => false
    }

  override def hashCode(): Int = left.hashCode + right.hashCode

  override def toString = s"PeerPair($left, $right)"
}
