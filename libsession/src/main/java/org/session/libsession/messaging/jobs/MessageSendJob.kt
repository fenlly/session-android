package org.session.libsession.messaging.jobs

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.Job.Companion.MAX_BUFFER_SIZE_BYTES
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.Data
import org.session.libsignal.utilities.HTTP
import org.session.libsignal.utilities.Log

class MessageSendJob(val message: Message, val destination: Destination) : Job {

    object AwaitingAttachmentUploadException : Exception("Awaiting attachment upload.")

    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0

    override val maxFailureCount: Int = 10

    companion object {
        val TAG = MessageSendJob::class.simpleName
        val KEY: String = "MessageSendJob"

        // Keys used for database storage
        private val MESSAGE_KEY = "message"
        private val DESTINATION_KEY = "destination"
    }

    override suspend fun execute(dispatcherName: String) {
        val messageDataProvider = MessagingModuleConfiguration.shared.messageDataProvider
        val message = message as? VisibleMessage
        val storage = MessagingModuleConfiguration.shared.storage

        // do not attempt to send if the message is marked as deleted
        message?.sentTimestamp?.let{
            if(messageDataProvider.isDeletedMessage(it)){
                return@execute
            }
        }

        val sentTimestamp = this.message.sentTimestamp
        val sender = storage.getUserPublicKey()
        if (sentTimestamp != null && sender != null) {
            storage.markAsSending(sentTimestamp, sender)
        }

        if (message != null) {
            if (!messageDataProvider.isOutgoingMessage(message.sentTimestamp!!) && message.reaction == null) return // The message has been deleted
            val attachmentIDs = mutableListOf<Long>()
            attachmentIDs.addAll(message.attachmentIDs)
            message.quote?.let { it.attachmentID?.let { attachmentID -> attachmentIDs.add(attachmentID) } }
            message.linkPreview?.let { it.attachmentID?.let { attachmentID -> attachmentIDs.add(attachmentID) } }
            val attachments = attachmentIDs.mapNotNull { messageDataProvider.getDatabaseAttachment(it) }
            val attachmentsToUpload = attachments.filter { it.url.isNullOrEmpty() }
            attachmentsToUpload.forEach {
                if (storage.getAttachmentUploadJob(it.attachmentId.rowId) != null) {
                    // Wait for it to finish
                } else {
                    val job = AttachmentUploadJob(it.attachmentId.rowId, message.threadID!!.toString(), message, id!!)
                    JobQueue.shared.add(job)
                }
            }
            if (attachmentsToUpload.isNotEmpty()) {
                this.handleFailure(dispatcherName, AwaitingAttachmentUploadException)
                return
            } // Wait for all attachments to upload before continuing
        }
        val isSync = destination is Destination.Contact && destination.publicKey == sender
        val promise = MessageSender.send(this.message, this.destination, isSync).success {
            this.handleSuccess(dispatcherName)
        }.fail { exception ->
            var logStacktrace = true

            when (exception) {
                // No need for the stack trace for HTTP errors
                is HTTP.HTTPRequestFailedException -> {
                    logStacktrace = false

                    if (exception.statusCode == 429) { this.handlePermanentFailure(dispatcherName, exception) }
                    else { this.handleFailure(dispatcherName, exception) }
                }
                is MessageSender.Error -> {
                    if (!exception.isRetryable) { this.handlePermanentFailure(dispatcherName, exception) }
                    else { this.handleFailure(dispatcherName, exception) }
                }
                else -> this.handleFailure(dispatcherName, exception)
            }

            if (logStacktrace) { Log.e(TAG, "Couldn't send message due to error", exception) }
            else { Log.e(TAG, "Couldn't send message due to error: ${exception.message}") }
        }
        try {
            promise.get()
        } catch (e: Exception) {
            Log.d(TAG, "Promise failed to resolve successfully", e)
        }
    }

    private fun handleSuccess(dispatcherName: String) {
        delegate?.handleJobSucceeded(this, dispatcherName)
    }

    private fun handlePermanentFailure(dispatcherName: String, error: Exception) {
        delegate?.handleJobFailedPermanently(this, dispatcherName, error)
    }

    private fun handleFailure(dispatcherName: String, error: Exception) {
        Log.w(TAG, "Failed to send $message::class.simpleName.")
        val message = message as? VisibleMessage
        if (message != null) {
            if (
                MessagingModuleConfiguration.shared.messageDataProvider.isDeletedMessage(message.sentTimestamp!!) ||
                !MessagingModuleConfiguration.shared.messageDataProvider.isOutgoingMessage(message.sentTimestamp!!)
                ) {
                return // The message has been deleted
            }
        }
        delegate?.handleJobFailed(this, dispatcherName, error)
    }

    override fun serialize(): Data {
        val kryo = Kryo()
        kryo.isRegistrationRequired = false
        // Message
        val messageOutput = Output(ByteArray(4096), MAX_BUFFER_SIZE_BYTES)
        kryo.writeClassAndObject(messageOutput, message)
        messageOutput.close()
        val serializedMessage = messageOutput.toBytes()
        // Destination
        val destinationOutput = Output(ByteArray(4096), MAX_BUFFER_SIZE_BYTES)
        kryo.writeClassAndObject(destinationOutput, destination)
        destinationOutput.close()
        val serializedDestination = destinationOutput.toBytes()
        // Serialize
        return Data.Builder()
            .putByteArray(MESSAGE_KEY, serializedMessage)
            .putByteArray(DESTINATION_KEY, serializedDestination)
            .build()
    }

    override fun getFactoryKey(): String {
        return KEY
    }

    class Factory : Job.Factory<MessageSendJob> {

        override fun create(data: Data): MessageSendJob? {
            val serializedMessage = data.getByteArray(MESSAGE_KEY)
            val serializedDestination = data.getByteArray(DESTINATION_KEY)
            val kryo = Kryo()
            kryo.isRegistrationRequired = false
            // Message
            val messageInput = Input(serializedMessage)
            val message: Message
            try {
                message = kryo.readClassAndObject(messageInput) as Message
            } catch (e: Exception) {
                Log.e("Loki", "Couldn't deserialize message send job.", e)
                return null
            }
            messageInput.close()
            // Destination
            val destinationInput = Input(serializedDestination)
            val destination: Destination
            try {
                destination = kryo.readClassAndObject(destinationInput) as Destination
            } catch (e: Exception) {
                Log.e("Loki", "Couldn't deserialize message send job.", e)
                return null
            }
            destinationInput.close()
            // Return
            return MessageSendJob(message, destination)
        }
    }
}