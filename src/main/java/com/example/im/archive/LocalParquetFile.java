package com.example.im.archive;

import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.apache.parquet.io.SeekableInputStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class LocalParquetFile implements InputFile, OutputFile {
    private final Path path;

    LocalParquetFile(Path path) { this.path = path; }

    @Override public long getLength() throws IOException { return Files.size(path); }
    @Override public SeekableInputStream newStream() throws IOException {
        return new ChannelInput(FileChannel.open(path, StandardOpenOption.READ));
    }
    @Override public PositionOutputStream create(long ignored) throws IOException {
        Files.createDirectories(path.getParent());
        return new ChannelOutput(FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
    }
    @Override public PositionOutputStream createOrOverwrite(long ignored) throws IOException {
        Files.createDirectories(path.getParent());
        return new ChannelOutput(FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE));
    }
    @Override public boolean supportsBlockSize() { return false; }
    @Override public long defaultBlockSize() { return 0; }
    @Override public String getPath() { return path.toString(); }

    private static final class ChannelOutput extends PositionOutputStream {
        private final FileChannel channel;
        private ChannelOutput(FileChannel channel) { this.channel = channel; }
        @Override public long getPos() throws IOException { return channel.position(); }
        @Override public void write(int value) throws IOException { channel.write(ByteBuffer.wrap(new byte[]{(byte) value})); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
            while (buffer.hasRemaining()) channel.write(buffer);
        }
        @Override public void flush() throws IOException { channel.force(false); }
        @Override public void close() throws IOException { channel.close(); }
    }

    private static final class ChannelInput extends SeekableInputStream {
        private final FileChannel channel;
        private ChannelInput(FileChannel channel) { this.channel = channel; }
        @Override public long getPos() throws IOException { return channel.position(); }
        @Override public void seek(long position) throws IOException { channel.position(position); }
        @Override public int read() throws IOException {
            ByteBuffer one = ByteBuffer.allocate(1);
            return channel.read(one) < 0 ? -1 : Byte.toUnsignedInt(one.array()[0]);
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            return channel.read(ByteBuffer.wrap(bytes, offset, length));
        }
        @Override public void readFully(byte[] bytes) throws IOException { readFully(bytes, 0, bytes.length); }
        @Override public void readFully(byte[] bytes, int offset, int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new java.io.EOFException();
        }
        @Override public int read(ByteBuffer buffer) throws IOException { return channel.read(buffer); }
        @Override public void readFully(ByteBuffer buffer) throws IOException {
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new java.io.EOFException();
        }
        @Override public void close() throws IOException { channel.close(); }
    }
}
