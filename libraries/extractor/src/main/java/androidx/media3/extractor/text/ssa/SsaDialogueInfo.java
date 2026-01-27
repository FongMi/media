package androidx.media3.extractor.text.ssa;

final class SsaDialogueInfo implements Comparable<SsaDialogueInfo> {

  public final long startTimeUs;
  public final long endTimeUs;
  public final int layer;
  public final String styleName;
  public final String rawText;
  public final float marginLeft;
  public final float marginRight;
  public final float marginVertical;

  public SsaDialogueInfo(long startTimeUs, long endTimeUs, int layer, String styleName, String rawText, float marginLeft, float marginRight, float marginVertical) {
    this.startTimeUs = startTimeUs;
    this.endTimeUs = endTimeUs;
    this.layer = layer;
    this.styleName = styleName;
    this.rawText = rawText;
    this.marginLeft = marginLeft;
    this.marginRight = marginRight;
    this.marginVertical = marginVertical;
  }

  @Override
  public int compareTo(SsaDialogueInfo other) {
    int res = Long.compare(this.startTimeUs, other.startTimeUs);
    return res != 0 ? res : Integer.compare(this.layer, other.layer);
  }
}
