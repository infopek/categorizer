package categorizer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import categorizer.data.AndroidAlbumRepository

class MainActivity : ComponentActivity() {
    private lateinit var albumRepository: AndroidAlbumRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        albumRepository = AndroidAlbumRepository(applicationContext)
        setContent {
            CategorizerApp(repository = albumRepository)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        albumRepository.close()
    }
}
