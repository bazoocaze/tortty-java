package checks;

import ui.Fonts;

/**
 * Which face of a family a font name is (`B36`). The point is not to print text:
 * it is to say "this family has no bold" instead of letting Java2D smear the
 * regular glyphs into a fake one.
 */
public final class FontsChecks {

    private FontsChecks() {}

    public static void run(Check c) {
        String fam = "DejaVu Sans Mono";
        c.eq("the regular face is plain", Fonts.styleOf(fam, "DejaVu Sans Mono"), Fonts.PLAIN);
        c.eq("the Bold face is bold", Fonts.styleOf(fam, "DejaVu Sans Mono Bold"), Fonts.BOLD);
        c.eq("the Oblique face is italic", Fonts.styleOf(fam, "DejaVu Sans Mono Oblique"), Fonts.ITALIC);
        c.eq("the Italic face is italic too", Fonts.styleOf(fam, "DejaVu Sans Mono Italic"), Fonts.ITALIC);
        c.eq("Bold Oblique is both", Fonts.styleOf(fam, "DejaVu Sans Mono Bold Oblique"), Fonts.BOLD_ITALIC);
        c.eq("case does not matter", Fonts.styleOf(fam, "dejavu sans mono bold"), Fonts.BOLD);
        c.eq("a hyphenated name still matches", Fonts.styleOf(fam, "DejaVu-Sans-Mono-Bold"), Fonts.BOLD);

        // the family has to end at a word boundary: "DejaVu Sans" is a prefix of
        // "DejaVu Sans Mono" and must not borrow its bold
        c.eq("another family is not this one", Fonts.styleOf(fam, "DejaVu Sans Bold"), -1);
        c.eq("nor is a longer name", Fonts.styleOf("DejaVu Sans", "DejaVu Sans Mono Bold"), -1);
        c.eq("a face of the right family", Fonts.styleOf("DejaVu Sans", "DejaVu Sans Bold"), Fonts.BOLD);
        c.eq("empty names are nothing", Fonts.styleOf("", "DejaVu Sans Mono Bold"), -1);
        c.eq("null is nothing", Fonts.styleOf(fam, null), -1);

        // a family Java knows nothing about carries no styled face at all
        c.eq("an unknown family has no bold face",
                Fonts.face(new java.awt.Font[] {}, fam, Fonts.BOLD), null);

        java.awt.Font plain = new java.awt.Font("Dialog", java.awt.Font.PLAIN, 12);
        java.awt.Font[] quad = Fonts.faces(new java.awt.Font[] {}, "No Such Family", plain);
        c.is("no face means the plain one for bold", quad[1] == plain);
        c.is("and for italic", quad[2] == plain);
        c.is("and for bold italic", quad[3] == plain);
        c.eq("four faces come back", quad.length, 4);
    }
}
