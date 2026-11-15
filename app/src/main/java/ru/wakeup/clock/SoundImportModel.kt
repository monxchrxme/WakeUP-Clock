package ru.wakeup.clock

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import java.util.concurrent.Executors

/** Keep an import alive across rotation, without retaining the activity or blocking its UI thread. */
class SoundImportModel : ViewModel() {
    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Ready(val choice: SoundChoice) : State
        data class Failed(val message: String) : State
    }
    var state: State = State.Idle
        private set
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var observer: ((State) -> Unit)? = null

    fun observe(action: (State) -> Unit) { observer = action; action(state) }
    fun detach() { observer = null }
    fun consume() { state = State.Idle }

    fun import(context: Context, uri: Uri) {
        if (state == State.Loading) return
        state = State.Loading
        observer?.invoke(state)
        val app = context.applicationContext
        worker.execute {
            val result = try { State.Ready(CustomSoundImporter.import(app, uri)) }
                catch (error: Exception) { State.Failed(if (error is SoundImportException) error.message!!
                    else "Не удалось прочитать аудиофайл. Выбери его заново и проверь свободное место в памяти.") }
            handler.post { state = result; observer?.invoke(state) }
        }
    }
    override fun onCleared() { observer = null; worker.shutdown() }
}
