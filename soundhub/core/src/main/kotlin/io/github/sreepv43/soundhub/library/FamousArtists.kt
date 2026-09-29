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
            "Hindi",
            "Arijit Singh", "Lata Mangeshkar", "Kishore Kumar", "Mohammed Rafi", "Asha Bhosle", "Shreya Ghoshal",
            "Sonu Nigam", "Atif Aslam", "Kumar Sanu", "Udit Narayan", "Alka Yagnik", "Sunidhi Chauhan", "Neha Kakkar",
            "Jubin Nautiyal", "Armaan Malik", "Rahat Fateh Ali Khan", "Shankar Mahadevan", "Hariharan", "Mukesh",
            "Manna Dey", "Pritam", "Amit Trivedi", "Vishal-Shekhar", "Shankar-Ehsaan-Loy", byName("A. R. Rahman", "Rahman"),
            byName("R. D. Burman", "Burman"), "Honey Singh", "Badshah", "Kailash Kher", "Sukhwinder Singh", "Lucky Ali",
            "Pankaj Udhas",
        ),
        genre(
            "Tamil",
            byName("A. R. Rahman", "Rahman"), "Ilaiyaraaja", "Yuvan Shankar Raja", "Anirudh Ravichander", "Harris Jayaraj",
            "Sid Sriram", byName("S. P. Balasubrahmanyam", "Balasubrahmanyam"), byName("K. S. Chithra", "Chithra"),
            byName("S. Janaki", "Janaki"), byName("P. Susheela", "Susheela"), byName("M. S. Viswanathan", "Viswanathan"),
            byName("T. M. Soundararajan", "Soundararajan"), "Hariharan", "Shankar Mahadevan", "Haricharan", "Vijay Antony",
            byName("G. V. Prakash Kumar", "Prakash Kumar"), byName("D. Imman", "Imman"), "Santhosh Narayanan", "Ghibran",
            "Sean Roldan", "Hiphop Tamizha", "Chinmayi", "Shweta Mohan", "Andrea Jeremiah", "Vidyasagar", "Swarnalatha",
            "Vani Jairam", "Unni Menon",
        ),
        genre(
            "Malayalam",
            byName("K. J. Yesudas", "Yesudas"), byName("K. S. Chithra", "Chithra"), byName("M. G. Sreekumar", "Sreekumar"),
            byName("M. Jayachandran", "Jayachandran"), "Vidyasagar", "Gopi Sundar", "Bijibal", "Raveendran", "Ouseppachan",
            byName("G. Devarajan", "Devarajan"), byName("V. Dakshinamoorthy", "Dakshinamoorthy"),
            byName("M. S. Baburaj", "Baburaj"), "Sujatha Mohan", "Vani Jairam", "Vineeth Sreenivasan", "Najim Arshad",
            "Hesham Abdul Wahab", "Jakes Bejoy", "Sushin Shyam", "Madhu Balakrishnan", "Vidhu Prathap",
            byName("G. Venugopal", "Venugopal"), "Shaan Rahman", "Rex Vijayan", "Salil Chowdhury", "Job Kurian",
            "Thaikkudam Bridge", "Avial", "Motherjane",
        ),
        genre(
            "Telugu",
            byName("M. M. Keeravani", "Keeravani"), "Devi Sri Prasad", byName("S. S. Thaman", "Thaman"), "Sid Sriram",
            byName("S. P. Balasubrahmanyam", "Balasubrahmanyam"), byName("P. Susheela", "Susheela"), byName("S. Janaki", "Janaki"),
            "Ghantasala", "Mickey J Meyer", "Anirudh Ravichander", "Armaan Malik", "Shreya Ghoshal", "Rahul Sipligunj",
            "Mangli", "Chinmayi", "Sunitha",
        ),
        genre(
            "Pop",
            "Michael Jackson", "Madonna", "Taylor Swift", "Adele", "Ed Sheeran", "Beyoncé", "Rihanna", "Bruno Mars",
            "Lady Gaga", "Ariana Grande", "Katy Perry", "Justin Bieber", "Dua Lipa", "Billie Eilish", "Harry Styles",
            "Olivia Rodrigo", "Whitney Houston", "ABBA", "Sia", "Shakira", "Charlie Puth", "Sabrina Carpenter",
            "Selena Gomez", "Miley Cyrus", "Britney Spears", "Justin Timberlake", "Backstreet Boys", "Spice Girls",
            "Camila Cabello", "Lizzo",
        ),
        genre(
            "Rock",
            "The Beatles", "Led Zeppelin", "Pink Floyd", "Queen", "The Rolling Stones", "AC/DC", "Nirvana", "U2",
            "Guns N' Roses", "Eagles", "Fleetwood Mac", "Dire Straits", "Coldplay", "Radiohead", "Foo Fighters",
            "Red Hot Chili Peppers", "Bon Jovi", "Linkin Park", "The Doors", "David Bowie", "Aerosmith", "Deep Purple",
            "The Who", "Rush", "Van Halen", "Green Day", "Oasis", "Arctic Monkeys", "Pearl Jam", "The Police", "Journey",
            "Toto", "The Cure",
        ),
        genre(
            "Metal",
            "Metallica", "Iron Maiden", "Black Sabbath", "Judas Priest", "Megadeth", "Slayer", "Pantera", "Tool",
            "System of a Down", "Rammstein", "Slipknot", "Dream Theater", "Opeth", "Gojira", "Sepultura", "Motörhead",
            "Ozzy Osbourne", "Mastodon", "Nightwish", "Lamb of God", "Anthrax", "Testament", "Sabaton",
        ),
        genre(
            "Hip-hop",
            "Eminem", "2Pac", "The Notorious B.I.G.", "Jay-Z", "Kanye West", "Kendrick Lamar", "Drake", "Nas",
            "Dr. Dre", "Snoop Dogg", "Travis Scott", "J. Cole", "OutKast", "Wu-Tang Clan", "50 Cent", "Lil Wayne",
            "Nicki Minaj", "Cardi B", "Post Malone", "Tyler, the Creator", "Run-DMC", "A Tribe Called Quest", "Public Enemy",
        ),
        genre(
            "R&B and soul",
            "Marvin Gaye", "Stevie Wonder", "Aretha Franklin", "Prince", "Whitney Houston", "Usher", "Alicia Keys",
            "The Weeknd", "Frank Ocean", "SZA", "Sade", "Al Green", "Otis Redding", "Ray Charles", "Lauryn Hill",
            "Luther Vandross", "Mary J. Blige", "Anita Baker", "Toni Braxton", "Boyz II Men", "Erykah Badu", "Beyoncé",
            "Earth, Wind & Fire", "Sam Cooke", "Bill Withers",
        ),
        genre(
            "Electronic",
            "Daft Punk", "The Chemical Brothers", "Deadmau5", "Avicii", "Calvin Harris", "Kraftwerk", "Aphex Twin",
            "Massive Attack", "The Prodigy", "Moby", "Tiësto", "David Guetta", "Swedish House Mafia",
            "Jean-Michel Jarre", "Vangelis", "Skrillex", "Zedd", "Martin Garrix", "Kygo", "Justice", "Boards of Canada",
            "Four Tet", "Burial", "Röyksopp", "Depeche Mode", "New Order", "Tangerine Dream",
        ),
        genre(
            "Jazz",
            "Miles Davis", "John Coltrane", "Louis Armstrong", "Ella Fitzgerald", "Duke Ellington", "Thelonious Monk",
            "Charles Mingus", "Bill Evans", "Herbie Hancock", "Dave Brubeck", "Chet Baker", "Billie Holiday",
            "Nina Simone", "Pat Metheny", "Diana Krall", "Sonny Rollins", "Art Blakey", "Oscar Peterson", "Wes Montgomery",
            "Stan Getz", "Norah Jones", "Dizzy Gillespie", "Charlie Parker",
        ),
        genre(
            "Classical",
            "Bach", "Mozart", "Beethoven", "Chopin", "Tchaikovsky", "Vivaldi", "Debussy", "Brahms", "Mahler",
            "Stravinsky", "Yo-Yo Ma", "Lang Lang", "Herbert von Karajan", "Glenn Gould", "Rachmaninoff", "Schubert",
            "Handel", "Verdi", "Puccini", "Ravel", "Shostakovich", "Haydn", "Pavarotti", "Bruckner",
        ),
        genre(
            "Film scores",
            "Hans Zimmer", "John Williams", "Ennio Morricone", "Howard Shore", "James Horner", "Ludwig Göransson",
            "Danny Elfman", "Alan Silvestri", "Vangelis", "Ramin Djawadi", "Joe Hisaishi", byName("A. R. Rahman", "Rahman"),
            "Michael Giacchino", "Thomas Newman", "Jerry Goldsmith", "Bernard Herrmann", "Nino Rota", "Max Richter",
            "Trent Reznor", "Ilaiyaraaja",
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
            "Juanes", "Marc Anthony", "Rosalía", "Buena Vista Social Club", "Luis Miguel", "Daddy Yankee", "Karol G",
            "Maluma", "Ozuna", "Celia Cruz", "Carlos Vives", "Gipsy Kings",
        ),
        genre(
            "Country and folk",
            "Johnny Cash", "Dolly Parton", "Willie Nelson", "Garth Brooks", "Shania Twain", "Bob Dylan",
            "Simon & Garfunkel", "Joni Mitchell", "Neil Young", "Chris Stapleton", "Kacey Musgraves", "John Denver",
            "Luke Combs", "Morgan Wallen", "Tim McGraw", "George Strait", "Alan Jackson", "Patsy Cline", "Hank Williams",
            "Emmylou Harris", "Carrie Underwood",
        ),
        genre(
            "K-pop and J-pop",
            "BTS", "BLACKPINK", "TWICE", "EXO", "NewJeans", "Stray Kids", "Red Velvet", "Jay Chou", "Utada Hikaru", "YOASOBI",
            "ITZY", "aespa", "SEVENTEEN", "Girls' Generation", "PSY", "BIGBANG", "Ado",
        ),
    )

    /** "Beyoncé" → "Beyonce", "AC/DC" → "AC DC", "Guns N' Roses" → "Guns N Roses": what file names contain. */
    fun searchText(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
}

/**
 * The listener's own artists (added on the Artists page), kept as one name per line, newest first.
 * They are searched by the name as typed, so the name should be as it appears in file names.
 */
object MyArtists {
    const val GENRE = "My artists"
    private const val MAX = 200
    private const val MAX_NAME = 80

    fun parse(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** [text] with [name] added at the front (unchanged when it is blank or already there). */
    fun add(text: String, name: String): String {
        val clean = name.trim().replace(Regex("""\s+"""), " ").take(MAX_NAME)
        val list = parse(text)
        if (clean.isEmpty() || list.any { it.equals(clean, ignoreCase = true) }) return text
        return (listOf(clean) + list).take(MAX).joinToString("\n")
    }

    fun remove(text: String, name: String): String = parse(text).filterNot { it == name }.joinToString("\n")

    fun genre(text: String) = ArtistGenre(GENRE, parse(text).map { FamousArtist(it) })
}
