package term;

/**
 * DEC Special Graphics (G0/G1 via {@code ESC ( 0}) — the table that draws the
 * {@code mc} frames. Without it {@code mc} shows {@code lqqqqqk} around the
 * panel, and {@code lqqk} is the cheap test for "the terminal is sane".
 *
 * <p>The table is the one xterm/ncurses implement (terminfo's {@code acsc}):
 * {@code j=┘ k=┐ l=┌ m=└ n=┼ o..s=scan lines q=─}, which is <em>not</em> the
 * literal VT100 order — the scan lines and the corners are shifted by one in
 * the historical table, and every practising terminal follows this one.
 */
public final class Charset {
    private Charset() {}

    public static final int ASCII = 0;
    public static final int DEC_GRAPHICS = 1;

    /** 0x5f..0x7e, indexed by {@code c - 0x5f}. */
    private static final int[] DEC = {
        0x00A0, // _ hard space
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
        0x2518, // j lower-right corner
        0x2510, // k upper-right corner
        0x250C, // l upper-left corner
        0x2514, // m lower-left corner
        0x253C, // n crossing lines
        0x23BA, // o horizontal scan line 1
        0x23BB, // p horizontal scan line 3
        0x2500, // q horizontal line
        0x23BC, // r horizontal scan line 7
        0x23BD, // s horizontal scan line 9
        0x251C, // t left tee
        0x2524, // u right tee
        0x2534, // v bottom tee
        0x252C, // w top tee
        0x2502, // x vertical line
        0x2264, // y less than or equal
        0x2265, // z greater than or equal
        0x03C0, // { pi
        0x2260, // | not equal
        0x00A3, // } pound sterling
        0x00B7, // ~ bullet
    };

    public static int map(int charset, int ch) {
        if (charset == DEC_GRAPHICS && ch >= 0x5F && ch <= 0x7E) {
            return DEC[ch - 0x5F];
        }
        return ch;
    }
}
