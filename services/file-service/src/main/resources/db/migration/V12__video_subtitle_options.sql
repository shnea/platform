ALTER TABLE files ADD COLUMN video_options jsonb NOT NULL DEFAULT '{}';
ALTER TABLE file_media_jobs ADD COLUMN options jsonb NOT NULL DEFAULT '{}';
ALTER TABLE file_videos ADD COLUMN subtitles jsonb;
