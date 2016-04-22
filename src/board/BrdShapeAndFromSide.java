/*
 *  Copyright (C) 2014  Alfons Wirtz  
 *   website www.freerouting.net
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License at <http://www.gnu.org/licenses/> 
 *   for more details.
 *
 * Created on 1. September 2003, 08:40
 */

package board;

import planar.PlaLineInt;
import planar.PlaPointFloat;
import planar.PlaSide;
import planar.Polyline;
import planar.ShapeTile;
import board.items.BrdTracePolyline;
import board.shape.ShapeSearchTree;

/**
 * Used in the shove algorithm to calculate the fromside for pushing and to cut off dog ears of the trace shape.
 *
 * @author Alfons Wirtz
 */

public final class BrdShapeAndFromSide
   {
   public final ShapeTile shape;
   public final BrdFromSide from_side;
   
   /**
    * Used in the shove algorithm to calculate the fromside for pushing and to cut off dog ears of the trace shape. In the check
    * shove functions, p_in_shove_check is expected to be true. In the actual shove functions p_in_shove_check is expected to be
    * false.
    */
   public BrdShapeAndFromSide(BrdTracePolyline p_trace, int p_index, boolean p_orthogonal, boolean p_in_shove_check)
      {
      ShapeSearchTree search_tree = p_trace.r_board.search_tree_manager.get_default_tree();
      ShapeTile curr_shape = p_trace.get_tree_shape(search_tree, p_index);
      BrdFromSide curr_from_side = null;
      boolean cut_off_at_start = false;
      boolean cut_off_at_end = false;
      if (p_orthogonal)
         {
         curr_shape = curr_shape.bounding_box();
         }
      else
         {
         // prevent dog ears at the start and the end of the substitute trace
         curr_shape = curr_shape.to_Simplex();
         PlaLineInt end_cutline = calc_cutline_at_end(p_index, p_trace);
         if (end_cutline != null)
            {
            ShapeTile cut_plane = ShapeTile.get_instance(end_cutline);
            ShapeTile tmp_shape = curr_shape.intersection(cut_plane);
            if (tmp_shape != curr_shape && !tmp_shape.is_empty())
               {
               curr_shape = tmp_shape.to_Simplex();
               cut_off_at_end = true;
               }
            }
         PlaLineInt start_cutline = calc_cutline_at_start(p_index, p_trace);
         if (start_cutline != null)
            {
            ShapeTile cut_plane = ShapeTile.get_instance(start_cutline);
            ShapeTile tmp_shape = curr_shape.intersection(cut_plane);
            if (tmp_shape != curr_shape && !tmp_shape.is_empty())
               {
               curr_shape = tmp_shape.to_Simplex();
               cut_off_at_start = true;

               }
            }
         int from_side_no = -1;
         PlaLineInt curr_cut_line = null;
         if (cut_off_at_start == true)
            {
            curr_cut_line = start_cutline;
            from_side_no = curr_shape.border_line_index(curr_cut_line);
            }
         if (from_side_no < 0 && cut_off_at_end == true)
            {
            curr_cut_line = end_cutline;
            from_side_no = curr_shape.border_line_index(curr_cut_line);
            }
         if (from_side_no >= 0)
            {
            PlaPointFloat border_intersection = curr_cut_line.intersection_approx(curr_shape.border_line(from_side_no));
            
            if ( !  border_intersection.is_NaN() )
               curr_from_side = new BrdFromSide(from_side_no, border_intersection);
            }
         }
      
      if (curr_from_side == null && !p_in_shove_check)
         {
         // In p_in_shove_check, using this calculation may produce an undesired stack_level > 1 in ShapeTraceEntries.
         curr_from_side = new BrdFromSide(p_trace.polyline(), p_index, curr_shape);
         }
      
      shape = curr_shape;
      from_side = curr_from_side;
      }

   private static PlaLineInt calc_cutline_at_end(int p_index, BrdTracePolyline p_trace)
      {
      Polyline trace_lines = p_trace.polyline();
      ShapeSearchTree search_tree = p_trace.r_board.search_tree_manager.get_default_tree();
      if (p_index == trace_lines.lines_arr.length - 3
            || trace_lines.corner_approx(trace_lines.lines_arr.length - 2).distance(trace_lines.corner_approx(p_index + 1)) < p_trace.get_compensated_half_width(search_tree))
         {

         PlaLineInt curr_line = trace_lines.lines_arr[trace_lines.lines_arr.length - 1];
         PlaPointFloat is = trace_lines.corner_approx(trace_lines.lines_arr.length - 3);
         PlaLineInt cut_line;
         if (curr_line.side_of(is) == PlaSide.ON_THE_LEFT)
            {
            cut_line = curr_line.opposite();
            }
         else
            {
            cut_line = curr_line;
            }
         return cut_line;
         }
      return null;
      }

   private static PlaLineInt calc_cutline_at_start(int p_index, BrdTracePolyline p_trace)
      {
      Polyline trace_lines = p_trace.polyline();
      ShapeSearchTree search_tree = p_trace.r_board.search_tree_manager.get_default_tree();
      if (p_index == 0 || trace_lines.corner_approx(0).distance(trace_lines.corner_approx(p_index)) < p_trace.get_compensated_half_width(search_tree))
         {
         PlaLineInt curr_line = trace_lines.lines_arr[0];
         PlaPointFloat is = trace_lines.corner_approx(1);
         PlaLineInt cut_line;
         if (curr_line.side_of(is) == PlaSide.ON_THE_LEFT)
            {
            cut_line = curr_line.opposite();
            }
         else
            {
            cut_line = curr_line;
            }
         return cut_line;
         }
      return null;
      }
   }