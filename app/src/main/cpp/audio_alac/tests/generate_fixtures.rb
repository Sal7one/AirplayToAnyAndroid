# SPDX-License-Identifier: Apache-2.0
require 'fileutils'
require 'json'
require 'open3'
require 'tmpdir'

destination = File.expand_path('../../../../androidTest/assets/alac', __dir__)
FileUtils.mkdir_p(destination)

def command(*args)
  output, error, status = Open3.capture3(*args)
  raise "#{args.first}: #{error}" unless status.success?
  output
end

puts command('ffmpeg', '-version').lines.first
Dir.mktmpdir('fossmirror-alac-fixtures-') do |temporary|
  [['ramp4096', 4096, false], ['ramp352', 352, false], ['noise352', 352, true]].each do |name, frames, noise|
    state = 0x13579bdf
    samples = frames.times.flat_map do |i|
      if noise
        2.times.map do
          state = (1664525 * state + 1013904223) & 0xffffffff
          (state >> 16) - 32768
        end
      else
        [((i * 31) % 60001) - 30000, 28000 - ((i * 17) % 56001)]
      end
    end
    pcm = File.join(destination, "#{name}.pcm")
    packet = File.join(destination, "#{name}.alac")
    container = File.join(temporary, "#{name}.m4a")
    File.binwrite(pcm, samples.pack('s<*'))
    command('ffmpeg', '-v', 'error', '-y', '-f', 's16le', '-ar', '44100', '-ac', '2',
            '-i', pcm, '-c:a', 'alac', container)
    packets = JSON.parse(command('ffprobe', '-v', 'error', '-select_streams', 'a:0',
                                 '-show_packets', '-of', 'json', container)).fetch('packets')
    raise 'Expected exactly one ALAC packet' unless packets.length == 1
    command('ffmpeg', '-v', 'error', '-y', '-i', container, '-map', '0:a:0', '-c', 'copy', '-f', 'data', packet)
    independent = command('ffmpeg', '-v', 'error', '-i', container, '-f', 's16le', '-c:a', 'pcm_s16le', '-')
    raise 'Independent FFmpeg decode changed PCM' unless independent.b == File.binread(pcm)
    bytes = File.binread(packet)
    escape = (bytes[2].ord >> 1) & 1
    raise 'Expected an entropy-coded ramp' if !noise && escape != 0
    raise 'Expected an escape-coded noise packet' if noise && escape != 1
    puts "#{name}: #{bytes.bytesize} packet bytes, #{samples.length * 2} PCM bytes, escape=#{escape}"
  end
end
