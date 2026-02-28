-- Add notebook_image_id column to workspaces table for custom image tracking
ALTER TABLE workspaces ADD COLUMN notebook_image_id UUID REFERENCES notebook_images(id);
