package com.hyperbot.remote;

import java.util.ArrayList;
import java.util.List;

/** One-tap playlists built from the bundled animations. */
final class Moods {
    private Moods() { }

    static final class Mood {
        final String name;
        final String blurb;
        final String[] files;
        Mood(String name, String blurb, String... files) { this.name = name; this.blurb = blurb; this.files = files; }
    }

    static final Mood[] ALL = {
            new Mood("CHILL", "slow blossoms, side-eye, soft smiles",
                    "effect_sakura.qgif", "eye_look_left.qgif", "emotion_uwu.qgif", "theme_bee.qgif", "pop-cat.qgif"),
            new Mood("HYPER", "spinning, bouncing, breathing fire",
                    "action_pingpong.qgif", "effect_rotate.qgif", "emotion_dizzy.qgif", "theme_dragon.qgif", "theme_nose_fire.qgif"),
            new Mood("SLEEPY", "yawns, squints and a sneeze",
                    "action_sleepy.qgif", "action_yawn.qgif", "eye_squint.qgif", "action_sneeze_01.qgif", "effect_sakura.qgif"),
            new Mood("LOVE", "hearts, winks, uwu",
                    "emotion_love_02.qgif", "emotion_uwu.qgif", "eye_wink.qgif", "effect_sakura.qgif", "pop-cat.qgif"),
            new Mood("GRUMPY", "steam, devil horns, sulking",
                    "emotion_angry_03.qgif", "emotion_angry_04.qgif", "emotion_devil_02.qgif", "emotion_frustrated.qgif", "theme_nose_fire.qgif"),
    };

    static List<Qgif> resolve(Mood mood, Library library) {
        List<Qgif> out = new ArrayList<>();
        for (String file : mood.files) {
            Qgif q = library.find(file);
            if (q != null && !out.contains(q)) out.add(q);
        }
        return out;
    }
}
