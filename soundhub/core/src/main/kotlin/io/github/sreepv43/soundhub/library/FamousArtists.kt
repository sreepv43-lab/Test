package io.github.sreepv43.soundhub.library

import java.text.Normalizer

/** An artist on the Artists page. [query] is what is searched for (plain letters, so file names match). */
data class FamousArtist(val name: String, val query: String = FamousArtists.searchText(name))

data class ArtistGenre(val name: String, val artists: List<FamousArtist>)

/** Well-known artists by genre, to search Soulseek with one press. */
object FamousArtists {
    private fun genre(name: String, vararg artists: Any) = ArtistGenre(
        name,
        artists.map { if (it is FamousArtist) it else FamousArtist(it as String) },
    )

    /** Initials alone ("A. R.") find nothing useful: these search by surname, or keep the dots (R.E.M.). */
    private fun byName(name: String, query: String) = FamousArtist(name, query)

    val genres: List<ArtistGenre> = listOf(
        genre(
            "Dolby Atmos picks",
            "Pink Floyd", "The Beatles", "Queen", "Elton John", "Steven Wilson", "Taylor Swift", "The Weeknd",
            "Billie Eilish", "Coldplay", "Dua Lipa", "Kraftwerk", "Genesis", byName("R.E.M.", "R.E.M."), "Marvin Gaye", "Bob Marley",
            "Hans Zimmer", byName("A. R. Rahman", "Rahman"), "Ed Sheeran", "Daft Punk", "Tears for Fears",
        ),
        genre(
            "Pop",
            "Michael Jackson", "Madonna", "Taylor Swift", "Adele", "Ed Sheeran", "Beyoncé", "Rihanna", "Bruno Mars",
            "Lady Gaga", "Ariana Grande", "Katy Perry", "Justin Bieber", "Dua Lipa", "Billie Eilish", "Harry Styles",
            "Olivia Rodrigo", "Whitney Houston", "ABBA", "Sia", "Shakira",
        ),
        genre(
            "Rock",
            "The Beatles", "Led Zeppelin", "Pink Floyd", "Queen", "The Rolling Stones", "AC/DC", "Nirvana", "U2",
            "Guns N' Roses", "Eagles", "Fleetwood Mac", "Dire Straits", "Coldplay", "Radiohead", "Foo Fighters",
            "Red Hot Chili Peppers", "Bon Jovi", "Linkin Park", "The Doors", "David Bowie",
        ),
        genre(
            "Metal",
            "Metallica", "Iron Maiden", "Black Sabbath", "Judas Priest", "Megadeth", "Slayer", "Pantera", "Tool",
            "System of a Down", "Rammstein", "Slipknot", "Dream Theater", "Opeth", "Gojira",
        ),
        genre(
            "Hip-hop",
            "Eminem", "2Pac", "The Notorious B.I.G.", "Jay-Z", "Kanye West", "Kendrick Lamar", "Drake", "Nas",
            "Dr. Dre", "Snoop Dogg", "Travis Scott", "J. Cole", "OutKast", "Wu-Tang Clan", "50 Cent",
        ),
        genre(
            "R&B and soul",
            "Marvin Gaye", "Stevie Wonder", "Aretha Franklin", "Prince", "Whitney Houston", "Usher", "Alicia Keys",
            "The Weeknd", "Frank Ocean", "SZA", "Sade", "Al Green", "Otis Redding", "Ray Charles", "Lauryn Hill",
        ),
        genre(
            "Electronic",
            "Daft Punk", "The Chemical Brothers", "Deadmau5", "Avicii", "Calvin Harris", "Kraftwerk", "Aphex Twin",
            "Massive Attack", "The Prodigy", "Moby", "Tiësto", "David Guetta", "Swedish House Mafia",
            "Jean-Michel Jarre", "Vangelis",
        ),
        genre(
            "Jazz",
            "Miles Davis", "John Coltrane", "Louis Armstrong", "Ella Fitzgerald", "Duke Ellington", "Thelonious Monk",
            "Charles Mingus", "Bill Evans", "Herbie Hancock", "Dave Brubeck", "Chet Baker", "Billie Holiday",
            "Nina Simone", "Pat Metheny", "Diana Krall",
        ),
        genre(
            "Classical",
            "Bach", "Mozart", "Beethoven", "Chopin", "Tchaikovsky", "Vivaldi", "Debussy", "Brahms", "Mahler",
            "Stravinsky", "Yo-Yo Ma", "Lang Lang", "Herbert von Karajan", "Glenn Gould",
        ),
        genre(
            "Film scores",
            "Hans Zimmer", "John Williams", "Ennio Morricone", "Howard Shore", "James Horner", "Ludwig Göransson",
            "Danny Elfman", "Alan Silvestri", "Vangelis", "Ramin Djawadi", "Joe Hisaishi", byName("A. R. Rahman", "Rahman"),
        ),
        genre(
            "Indian film",
            byName("A. R. Rahman", "Rahman"), "Ilaiyaraaja", "Arijit Singh", "Lata Mangeshkar", "Kishore Kumar",
            "Mohammed Rafi", "Asha Bhosle", byName("S. P. Balasubrahmanyam", "Balasubrahmanyam"),
            byName("K. S. Chithra", "Chithra"), byName("K. J. Yesudas", "Yesudas"), "Shreya Ghoshal", "Sonu Nigam",
            "Anirudh Ravichander", "Sid Sriram", "Pritam", byName("M. M. Keeravani", "Keeravani"), "Harris Jayaraj",
            "Yuvan Shankar Raja", "Devi Sri Prasad", "Gopi Sundar", byName("M. Jayachandran", "Jayachandran"),
            "Vidyasagar",
        ),
        genre(
            "Indian classical and ghazal",
            "Ravi Shankar", "Zakir Hussain", byName("M. S. Subbulakshmi", "Subbulakshmi"), "Bhimsen Joshi",
            "Hariprasad Chaurasia", "Shivkumar Sharma", "Amjad Ali Khan", "Pandit Jasraj",
            byName("L. Subramaniam", "Subramaniam"), byName("U. Srinivas", "Srinivas mandolin"), "Bombay Jayashri",
            "Nusrat Fateh Ali Khan", "Ghulam Ali", "Jagjit Singh", "Mehdi Hassan",
        ),
        genre(
            "Latin",
            "Shakira", "Bad Bunny", "Santana", "Gloria Estefan", "Ricky Martin", "Enrique Iglesias", "J Balvin",
            "Juanes", "Marc Anthony", "Rosalía", "Buena Vista Social Club", "Luis Miguel",
        ),
        genre(
            "Country and folk",
            "Johnny Cash", "Dolly Parton", "Willie Nelson", "Garth Brooks", "Shania Twain", "Bob Dylan",
            "Simon & Garfunkel", "Joni Mitchell", "Neil Young", "Chris Stapleton", "Kacey Musgraves", "John Denver",
        ),
        genre(
            "K-pop and J-pop",
            "BTS", "BLACKPINK", "TWICE", "EXO", "NewJeans", "Stray Kids", "Red Velvet", "Jay Chou", "Utada Hikaru", "YOASOBI",
        ),
    )

    /** "Beyoncé" → "Beyonce", "AC/DC" → "AC DC", "Guns N' Roses" → "Guns N Roses": what file names contain. */
    fun searchText(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
}
