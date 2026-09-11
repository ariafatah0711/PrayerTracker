package com.prayertracker.app.core.sync

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GoogleAuthManager(private val context: Context) {

    companion object {
        val SCOPE_DRIVE_APPDATA = Scope("https://www.googleapis.com/auth/drive.appdata")
        val SCOPE_DRIVE_FILE = Scope("https://www.googleapis.com/auth/drive.file")
        val SCOPE_SPREADSHEETS = Scope("https://www.googleapis.com/auth/spreadsheets")

        val OAUTH_SCOPES = "oauth2:https://www.googleapis.com/auth/drive.appdata https://www.googleapis.com/auth/drive.file https://www.googleapis.com/auth/spreadsheets"
    }

    private val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(SCOPE_DRIVE_APPDATA, SCOPE_DRIVE_FILE, SCOPE_SPREADSHEETS)
        .build()

    val client: GoogleSignInClient = GoogleSignIn.getClient(context, gso)

    fun getSignedInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        val account = getSignedInAccount()?.account ?: return@withContext null
        try {
            GoogleAuthUtil.getToken(context, account, OAUTH_SCOPES)
        } catch (_: Exception) {
            null
        }
    }

    fun getSignInIntent(): Intent {
        return client.signInIntent
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        try {
            client.signOut()
        } catch (_: Exception) {}
    }
}
