// Use an integer for version numbers
version = 1

cloudstream {
    description = "Flix World YouTube: videos, channels, playlists and Shorts"
    authors = listOf("FlixWorld")

    // 0: Down, 1: Ok, 2: Slow, 3: Beta-only
    status = 1

    tvTypes = listOf("Other", "Live", "TvSeries")
    language = "en"

    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/0/09/" +
        "YouTube_full-color_icon_%282017%29.svg/3840px-YouTube_full-color_icon_%282017%29.svg.png"

    isCrossPlatform = true
}

android {
    buildFeatures {
        buildConfig = true
    }
}
