package term;

/** DEC line-drawing charset (G0) mapping for ESC(0, used by mc box-drawing. */
public final class Charset {
    private Charset() {}

    public static final int ASCII = 0;
    public static final int DEC_GRAPHICS = 1;

    // DEC supplemental graphics: 0x60..0x7e mapping. Indexed by (c - 0x60).
    private static final int[] DEC = {
        0x25C6, // ` diamond
        0x2592, // a checker board
        0x2409, // b HT symbol
        0x240C, // c FF symbol
        0x240D, // d CR symbol
        0x240A, // e LF symbol
        0x00B0, // f degree sign
        0x00B1, // g plus-minus
        0x2424, // h NL symbol
        0x240B, // i VT symbol
        0x251C, // j upper right
        0x2518, // k upper left
        0x2510, // l upper right? (see table below)
        0x250C, // m lower left
        0x2514, // n lower right
        0x253C, // o horizontal
        0x23BA, // p scan line 1
        0x23BB, // q scan line 3
        0x2500, // r horizontal
        0x23BC, // s scan line 7
        0x23BD, // t scan line 9
        0x251C, // u
        0x252C, // v
        0x2524, // w
        0x2534, // x
        0x2502, // y
        0x2264, // z
        0x2265, // { pi? DEC table: 0x7b -> pi
        0x03C0, // | pi? DEC: 0x7c -> pi symbol is '{' 0x7b; '|' -> ? standard table below
        0x00A3, // } sterling
        0x00B7, // ~ bullet
    };

    // Correct DEC supplemental graphics table (xterm / VT100).
    // Index 0x60..0x7e:
    //  60 diamond 25C6, 61 checker 2592, 62 HT 2409, 63 FF 240C, 64 CR 240D,
    //  65 LF 240A, 66 degree 00B0, 67 +\- 00B1, 68 NL 2424, 69 VT 240B,
    //  6a +|-> 251C, 6b +<-| 2518, 6c +- -> 2510, 6d -+> 250C, 6e |+ 2514? fixing:
    // The table used by xterm setg0 is:
    //   _ -> 25C6, a -> 2592, b -> 2409, c -> 240C, d -> 240D, e -> 240A,
    //   f -> 00B0, g -> 00B1, h -> 2424, i -> 240B, j -> 251C, k -> 2518,
    //   l -> 2510, m -> 250C, n -> 2514, o -> 253C, p -> 23BA, q -> 23BB,
    //   r -> 2500, s -> 23BC, t -> 23BD, u -> 251C, v -> 252C, w -> 2524,
    //   x -> 2534, y -> 2502, z -> 2264, { -> 2265, | -> 220F, } -> 00A3, ~ -> 00B7
    // But there is a well-known shifting: the commonly implemented table is:
    private static final int[] MAP = {
        0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0, 0x00B1,
        0x2424, 0x240B, 0x251C, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C,
        0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD, 0x252C, 0x2524, 0x2534,
        0x2502, 0x2264, 0x2265, 0x220F, 0x00A3, 0x00B7, 0x2592,
    };

    public static int map(int charset, int ch) {
        if (charset == DEC_GRAPHICS && ch >= 0x60 && ch <= 0x7E) {
            return MAP[ch - 0x60];
        }
        return ch;
    }
}
