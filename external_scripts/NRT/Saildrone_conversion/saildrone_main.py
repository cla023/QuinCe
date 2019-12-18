###############################################################################
### SAILDRONE DATA RETRIEVAL AND CONVERSION		                            ###
###############################################################################

### Description
# Script uses Saildrone API to download saildrone ocean and biogeo data,
# converts it from json to csv format, and shares it with QuinCe.


#------------------------------------------------------------------------------
### Import packages
import os
import saildrone_module as saildrone
from datetime import datetime
import shutil
import urllib
import json
import ast


###----------------------------------------------------------------------------
### Handling directories
###----------------------------------------------------------------------------

# Store path to the main script directory
script_dir = os.path.dirname(os.path.realpath(__file__))

# Create a data directory if it does not already exist
if not os.path.isdir('./data_files'):
	os.mkdir(os.path.join(script_dir,'data_files'))

# Store path to the data directory
data_dir = os.path.join(script_dir,'data_files')

# Create new archive directry with current timestamp as name
now = datetime.now()
dt_string = now.strftime("%Y%m%dT%H%M%S")
archive_path = os.path.join(data_dir, str(dt_string))
os.mkdir(archive_path)


###----------------------------------------------------------------------------
### API
###----------------------------------------------------------------------------

# Create authentication token
token = saildrone.auth()

# See what can be downloaded
access_list = saildrone.check_available(token)



#-----------------
# Find out what's new since previous download

# !!! Find out somehow what I have not downloaded before. Create
# !!! new dictionary with new data and use this in the 'get-data-loop' below.


#-----------------
# Download data

# !!! Get 500 error when try to download all at once)
# !!! Add try except! urllib.error module
#for drone in access_list:
#	drone_id = drone['drone_id']
#	start = drone['start_date']
#	end = drone['end_date']

#	datasets = ['oceanographic', 'biogeochemical']
#	json_paths = []
#	for dataset in datasets:
#		json_path = saildrone.write_json(
#			data_dir, our_header, drone_id, dataset, start, end, token)
#		json_paths.append(json_path)


# !!! WHILE TESTING:
# The loop above will download ocean and biogeo file from one drone. It will
# also create 'json_paths'. While testing, create this manually.
ocean_path = os.path.join(data_dir,'1021_oceanographic.json')
biogeo_path = os.path.join(data_dir, '1021_biogeochemical.json')
json_paths = [ocean_path, biogeo_path]
drone_id = 1021


###----------------------------------------------------------------------------
### Convert to csv format and merge ocean and bio file
###----------------------------------------------------------------------------

# !!! Add one indent to this section when the download loop is not a comment.

# Convert each json to csv file. Move the json file to the archive
# folder. Store the new csv paths.
csv_paths = []
for path in json_paths :
	csv_path = saildrone.convert_to_csv(path)
	csv_paths.append(csv_path)
	shutil.move(path, os.path.join(archive_path, os.path.basename(path)))


# Merge if ocean and bio was downloaded (ocean path is then always first).
# Export the merged data to a csv file. Move the ocean and biogeo file to
# archive.
if len(csv_paths) == 2:
	merged_df = saildrone.merge_ocean_biogeo(csv_paths[0], csv_paths[1])

	merged_path = os.path.join(data_dir, str(drone_id) + '_merged.csv')
	merged_csv = merged_df.to_csv(
		merged_path, index=None, header=True, sep=',')

	for path in csv_paths:
		shutil.move(path, os.path.join(archive_path, os.path.basename(path)))


###----------------------------------------------------------------------------
### Send to QuinCe
###----------------------------------------------------------------------------

# All files in the data_folder are ready for export to QuinCe. Do quince
# want unmerged?