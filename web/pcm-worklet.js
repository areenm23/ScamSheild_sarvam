// Downsamples whatever the AudioContext runs at to 16 kHz mono and posts 100 ms Int16 (s16le) frames.
class PcmCapture extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ratio = sampleRate / 16000;
    this.pos = 0;
    this.frame = new Int16Array(1600);
    this.n = 0;
  }
  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (!ch) return true;
    let peak = 0;
    for (; this.pos < ch.length; this.pos += this.ratio) {
      const i = Math.floor(this.pos);
      const s = Math.max(-1, Math.min(1, ch[i]));
      peak = Math.max(peak, Math.abs(s));
      this.frame[this.n++] = s < 0 ? s * 0x8000 : s * 0x7fff;
      if (this.n === this.frame.length) {
        this.port.postMessage(this.frame.buffer.slice(0));
        this.n = 0;
      }
    }
    this.pos -= ch.length;
    this.port.postMessage({ peak });
    return true;
  }
}
registerProcessor("pcm-capture", PcmCapture);
