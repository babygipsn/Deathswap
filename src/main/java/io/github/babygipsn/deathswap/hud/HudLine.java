package io.github.babygipsn.deathswap.hud;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * One HUD line made of colored spans. A span with {@code tab >= 0} first moves the pen to that x
 * (relative to the HUD's left edge), which keeps columns aligned with a proportional font.
 */
public record HudLine(List<Span> spans) {
	public static final StreamCodec<ByteBuf, HudLine> CODEC = Span.CODEC.apply(ByteBufCodecs.list(32)).map(HudLine::new, HudLine::spans);

	public record Span(String text, int color, boolean bold, int tab) {
		public static final StreamCodec<ByteBuf, Span> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(256), Span::text,
				ByteBufCodecs.INT, Span::color,
				ByteBufCodecs.BOOL, Span::bold,
				ByteBufCodecs.VAR_INT, s -> s.tab + 1, // stored +1 so -1 (no tab) stays a non-negative varint
				(text, color, bold, tab) -> new Span(text, color, bold, tab - 1));
	}

	public static Builder builder() {
		return new Builder();
	}

	public static HudLine empty() {
		return new HudLine(List.of());
	}

	public String plain() {
		StringBuilder sb = new StringBuilder();
		for (Span s : this.spans) {
			if (s.tab >= 0 && !sb.isEmpty()) sb.append(' ');
			sb.append(s.text);
		}
		return sb.toString();
	}

	public static final class Builder {
		private final List<Span> spans = new ArrayList<>();

		public Builder text(String text, int color) {
			this.spans.add(new Span(text, color, false, -1));
			return this;
		}

		public Builder bold(String text, int color) {
			this.spans.add(new Span(text, color, true, -1));
			return this;
		}

		/** Text drawn starting at column x. */
		public Builder at(int x, String text, int color) {
			this.spans.add(new Span(text, color, false, x));
			return this;
		}

		public HudLine build() {
			return new HudLine(List.copyOf(this.spans));
		}
	}
}
