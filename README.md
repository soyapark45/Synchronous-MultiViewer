# Synchronous-MultiViewer
Soya Park <soya@kaist.ac.kr>, Massachusetts General Hospital / Vakoc Group

A plugin for [ImageJ](http://imagej.nih.gov/ij/)/[Fiji](http://fiji.sc/) for Synchronous Viewer for Efficient Analysis of Biomedical Applications.

Biomedical research often involves a heavy data analysis of comparing different volume stacks from the sample. Identifying temporal changes of the sample in a longitudinal study or comparing different contrast that complements one another are examples of such procedures, where a synchronous visualizaFon tool is essenFal for efficient analysis of three dimensional datasets.

This plug-in is for a synchronous view of multiple images and their cross-sectional views.
It supports the synchronizing of the cursor, location of window & magnification. 
Users are allowed to open at most three stacks. 

##Installation
1. Move the .java file to the directory ImageJ/plugins
2. Open the .java file in ImageJ
3. Compile and Run..

You wouldn't need the above procedures for the next time.
If you restart ImageJ, you could run it by clicking "Simultaneous MultiViewer" under the Plugins tab. 

##Reference
* SyncWindows: https://github.com/imagej/imagej1/blob/master/ij/plugin/frame/SyncWindows.java
* Orthogonal_Views: https://github.com/imagej/imagej1/blob/master/ij/plugin/Orthogonal_Views.java
