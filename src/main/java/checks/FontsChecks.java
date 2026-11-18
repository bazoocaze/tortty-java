package checks;

import ui.Fonts;

/**
 * Which face of a family a font name is (`B36`), and which family of a
 * `font_family` list gets used (`B80`). The point is not to print text:
 * it is to say "this family has no bold" instead of letting Java2D smear the
 * regular glyphs into a fake one.
 */
public final class FontsChecks {

    private FontsChecks() {}

    public static void run(Check c) {
        theFamilyList(c);
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

    /**
     * `font_family` is a list (`B80`): `Cascadia Mono, Consolas` means "this, else
     * that". The parse is a pure function of the line, and the choice is a pure
     * function of the names this machine has — so both are checked without a
     * display, and a family that is not installed cannot silently become another.
     */
    private static void theFamilyList(Check c) {
        java.util.List<String> two = Fonts.list("Cascadia Mono, Consolas");
        c.eq("two names are two families", two.size(), 2);
        c.eq("the order is the reader's", two.get(0), "Cascadia Mono");
        c.eq("and the second is kept whole", two.get(1), "Consolas");
        c.eq("blanks around a comma do not survive", Fonts.list("  A  ,   B ").get(0), "A");
        c.eq("an empty entry is not a family", Fonts.list("A,,B").size(), 2);
        c.eq("a trailing comma is not a family", Fonts.list("A,").size(), 1);
        c.eq("no line is no family", Fonts.list(null).size(), 0);
        c.eq("and neither is a blank one", Fonts.list("   ").size(), 0);
        c.eq("a single name is still a list", Fonts.list("Iosevka").get(0), "Iosevka");

        String[] have = {"DejaVu Sans Mono", "Consolas", "Noto Sans"};
        c.eq("the first name that exists wins",
                Fonts.first(java.util.List.of("Cascadia Mono", "Consolas", "Noto Sans"), have, "Monospaced"),
                "Consolas");
        c.eq("when none exists the fallback is used",
                Fonts.first(java.util.List.of("Nope", "Nada"), have, "Monospaced"), "Monospaced");
        c.eq("an empty list falls back too",
                Fonts.first(java.util.List.of(), have, "Monospaced"), "Monospaced");
        c.eq("the installed ones keep their order",
                String.join(",", Fonts.present(java.util.List.of("Noto Sans", "Nope", "Consolas"), have)),
                "Noto Sans,Consolas");
        c.eq("and a repeat is only one entry",
                Fonts.present(java.util.List.of("Consolas", "Consolas"), have).size(), 1);
    }
}
