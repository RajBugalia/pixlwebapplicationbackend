package com.pixl.backend.controller

import com.pixl.backend.dto.CreatePlaylistRequest
import com.pixl.backend.model.Playlist
import com.pixl.backend.repository.MediaRepository
import com.pixl.backend.repository.PlaylistRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.security.Principal
import com.pixl.backend.repository.UserRepository

@RestController
@RequestMapping("/api/playlists")
class PlaylistController(
    private val playlistRepository: PlaylistRepository,
    private val mediaRepository: MediaRepository,
    private val campaignRepository: com.pixl.backend.repository.CampaignRepository,
    private val userRepository: UserRepository
) {
    @GetMapping
    fun getAllPlaylists(principal: Principal): ResponseEntity<*> {
        val user = userRepository.findByEmail(principal.name).orElse(null)
        val playlists = playlistRepository.findAll().filter { it.creator?.id == user?.id }
        val response = playlists.map { p ->
            mapOf(
                "id" to p.id,
                "name" to p.name,
                "mediaCount" to p.mediaItems.size,
                "media" to p.mediaItems.map { m ->
                    mapOf(
                        "id" to m.id,
                        "filename" to m.filename,
                        "type" to m.type,
                        "publicUrl" to m.publicUrl,
                        "durationSeconds" to m.durationSeconds
                    )
                }
            )
        }
        return ResponseEntity.ok(response)
    }

    @PostMapping
    fun createPlaylist(@RequestBody request: CreatePlaylistRequest, principal: Principal): ResponseEntity<*> {
        val user = userRepository.findByEmail(principal.name).orElse(null)
        val mediaItems = mediaRepository.findAllById(request.mediaIds)
        
        request.mediaDurations?.forEach { (mediaId, duration) ->
            val item = mediaItems.find { it.id == mediaId }
            if (item != null && duration > 0) {
                item.durationSeconds = duration
                mediaRepository.save(item)
            }
        }
        
        val playlist = Playlist(
            name = request.name,
            mediaItems = mediaItems,
            creator = user
        )
        
        val saved = playlistRepository.save(playlist)
        
        return ResponseEntity.ok(mapOf(
            "id" to saved.id,
            "name" to saved.name,
            "message" to "Playlist created successfully"
        ))
    }

    @DeleteMapping("/{id}")
    fun deletePlaylist(@PathVariable id: Long): ResponseEntity<*> {
        val playlist = playlistRepository.findById(id).orElse(null)
            ?: return ResponseEntity.notFound().build<Any>()
            
        // Delete any campaigns that use this playlist
        val campaigns = campaignRepository.findAll()
        for (campaign in campaigns) {
            val usesInZone = campaign.zoneConfigJson?.contains("\"playlistId\":$id") == true
            if (campaign.playlist?.id == id || usesInZone) {
                campaignRepository.delete(campaign)
            }
        }
            
        playlistRepository.delete(playlist)
        return ResponseEntity.ok(mapOf("message" to "Playlist deleted successfully"))
    }

    @PutMapping("/{id}")
    fun updatePlaylist(@PathVariable id: Long, @RequestBody request: Map<String, Any>, principal: Principal): ResponseEntity<*> {
        val user = userRepository.findByEmail(principal.name).orElse(null)
            ?: return ResponseEntity.status(401).body(mapOf("error" to "Unauthorized"))

        val playlist = playlistRepository.findById(id).orElse(null)
            ?: return ResponseEntity.notFound().build<Any>()

        if (playlist.creator?.id != user.id) {
            val currentUser = userRepository.findById(user.id).orElseThrow()
            if (currentUser.role.name != "ROLE_MASTER") {
                return ResponseEntity.status(403).body(mapOf("error" to "Forbidden: Playlist does not belong to you"))
            }
        }

        val newName = request["name"] as? String
        if (!newName.isNullOrBlank()) {
            playlist.name = newName
        }

        val mediaIdsRaw = request["mediaIds"] as? List<*>
        if (mediaIdsRaw != null) {
            val mediaIds = mediaIdsRaw.mapNotNull { (it as? Number)?.toLong() }
            val items = mediaRepository.findAllById(mediaIds)
            playlist.mediaItems = items
        }

        val mediaDurations = request["mediaDurations"] as? Map<*, *>
        if (mediaDurations != null) {
            mediaDurations.forEach { (mIdRaw, durRaw) ->
                val mId = (mIdRaw.toString()).toLongOrNull()
                val dur = (durRaw as? Number)?.toInt() ?: (durRaw.toString()).toIntOrNull()
                if (mId != null && dur != null && dur > 0) {
                    val item = playlist.mediaItems.find { it.id == mId } ?: mediaRepository.findById(mId).orElse(null)
                    if (item != null) {
                        item.durationSeconds = dur
                        mediaRepository.save(item)
                    }
                }
            }
        }

        playlistRepository.save(playlist)

        return ResponseEntity.ok(mapOf("message" to "Playlist updated successfully", "name" to playlist.name))
    }
}

