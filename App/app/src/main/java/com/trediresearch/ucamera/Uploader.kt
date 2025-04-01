package com.trediresearch.ucamera

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import java.io.File
import java.io.InputStream
import java.util.Properties


class Uploader {
    val REMOTE_HOST = "192.168.0.109"
    val USERNAME = "admin"
    val PASSWORD = "ucamera"
    val REMOTE_PORT = 22

    fun copyFileToSftp(srcFile: File, ftpPath: String): Boolean {

        var jschSession: Session? = null

        try {
            val jsch = JSch()
            //jsch.setKnownHosts("/home/mkyong/.ssh/known_hosts")
            jschSession = jsch.getSession(USERNAME, REMOTE_HOST, REMOTE_PORT)
            jschSession.setPassword(PASSWORD)

            val config = Properties()
            config["StrictHostKeyChecking"] = "no"
            jschSession.setConfig(config)

            jschSession.connect(10000)
            val sftp: Channel = jschSession.openChannel("sftp")

            sftp.connect(5000)
            val channelSftp: ChannelSftp = sftp as ChannelSftp

            channelSftp.put(srcFile.absolutePath, ftpPath)
            channelSftp.exit()

        } catch (e: JSchException) {
            e.printStackTrace()
            return false
        } catch (e: SftpException) {
            e.printStackTrace()
            return false
        } finally {
            jschSession?.disconnect()
        }
        return true
    }


    fun runCmdToSSH(command:String):Boolean{
        var jschSession: Session? = null

        try {
            val jsch = JSch()
            //jsch.setKnownHosts("/home/mkyong/.ssh/known_hosts")
            jschSession = jsch.getSession(USERNAME, REMOTE_HOST, REMOTE_PORT)
            jschSession.setPassword(PASSWORD)

            val config = Properties()
            config["StrictHostKeyChecking"] = "no"
            jschSession.setConfig(config)

            jschSession.connect(10000)

            val channel: Channel = jschSession.openChannel("exec")
            (channel as ChannelExec).setCommand(command)
            channel.connect();
            channel.run()
            channel.disconnect()

        } catch (e: JSchException) {
            e.printStackTrace()
            return false
        } catch (e: SftpException) {
            e.printStackTrace()
            return false
        } finally {
            jschSession?.disconnect()
        }
        return true
    }

    fun uploadFirmware(): Boolean {

        val inputFile: InputStream = App.activity.getResources().openRawResource(R.raw.server)

        val file = createTempFile()
        inputFile.copyTo(file.outputStream())

        var result=copyFileToSftp(file,"/home/admin/firmware.zip")
        if(result)
            result=runCmdToSSH("sudo unzip firmware.zip -d /usr/local/bin/server;sudo reboot")

        return result

    }

}